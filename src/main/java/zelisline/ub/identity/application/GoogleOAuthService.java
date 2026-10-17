package zelisline.ub.identity.application;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import zelisline.ub.identity.api.dto.GoogleOAuthExchangeResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthPublicConfigResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthShopChoiceResponse;
import zelisline.ub.identity.api.dto.GoogleOAuthStartRequest;
import zelisline.ub.identity.api.dto.GoogleOAuthStartResponse;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.domain.OAuthLoginState;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserOAuthIdentity;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.OAuthLoginStateRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserOAuthIdentityRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;
import zelisline.ub.tenancy.application.PublicSignInDestinationService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Platform Google OAuth for merchant owners (authorization code + PKCE).
 *
 * <p>OAuth-only users get a random sentinel {@code password_hash} (never null) so
 * forgot-password can email them a link to set a real password later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleOAuthService {

    public static final String BIND_COOKIE = "ub.oauth_bind";
    /** Short-lived hint so BFF error redirects can return to office login. */
    public static final String NEXT_COOKIE = "ub.oauth_next";
    public static final String RETURN_HOST_COOKIE = "ub.oauth_return_host";
    /** HttpOnly continuation after Google verified an email that staffs several shops. */
    public static final String SHOP_PICK_COOKIE = "ub.google_shop_pick";
    private static final Pattern SHOP_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final OAuthLoginStateRepository oauthLoginStateRepository;
    private final UserOAuthIdentityRepository userOAuthIdentityRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BusinessRepository businessRepository;
    private final PublicSignInDestinationService publicSignInDestinationService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenCookieSupport refreshTokenCookieSupport;
    private final ObjectMapper objectMapper;

    @Value("${app.public.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.auth.signup-role-key:buyer}")
    private String signupRoleKey;

    @Transactional(readOnly = true)
    public GoogleOAuthPublicConfigResponse publicConfig() {
        return new GoogleOAuthPublicConfigResponse(
                platformIntegrationSettingsService.resolveGoogleOauth().ready());
    }

    @Transactional
    public ResponseEntity<GoogleOAuthStartResponse> start(
            HttpServletRequest http, GoogleOAuthStartRequest body) {
        var google = platformIntegrationSettingsService.resolveGoogleOauth();
        if (!google.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Google Sign-In is not configured");
        }
        String intent = body.intent() == null ? "" : body.intent().trim().toLowerCase(Locale.ROOT);
        if (!OAuthLoginState.INTENT_SIGN_IN.equals(intent)
                && !OAuthLoginState.INTENT_SIGN_UP.equals(intent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "intent must be sign_in or sign_up");
        }
        if (OAuthLoginState.INTENT_SIGN_UP.equals(intent)) {
            String businessId = firstNonBlank(body.businessId(), TenantRequestIds.resolveBusinessIdOrNull(http));
            if (businessId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Name your business before continuing with Google");
            }
        }

        String state = randomUrlSafe(32);
        String codeVerifier = randomUrlSafe(48);
        String nonce = randomUrlSafe(24);
        String browserBinding = randomUrlSafe(24);
        String redirectUri = resolveRedirectUri();

        OAuthLoginState row = new OAuthLoginState();
        row.setStateHash(TokenHasher.sha256Hex(state));
        row.setCodeVerifier(codeVerifier);
        row.setIntent(intent);
        row.setNextPath(sanitizeNext(body.next()));
        // Prefer explicit businessId (tenant /login or apex bounce) so custom-domain
        // starts still pin the shop after the apex-only Google callback.
        row.setBusinessId(
                firstNonBlank(body.businessId(), TenantRequestIds.resolveBusinessIdOrNull(http)));
        row.setOnboardDraftJson(encodeReturnHost(sanitizeReturnHost(body.returnHost())));
        row.setBrowserBinding(browserBinding);
        row.setNonce(nonce);
        row.setRedirectUri(redirectUri);
        row.setExpiresAt(Instant.now().plus(STATE_TTL));
        oauthLoginStateRepository.save(row);

        String authorizeUrl = GOOGLE_AUTH
                + "?client_id=" + enc(google.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(pkceChallenge(codeVerifier))
                + "&code_challenge_method=S256"
                + "&nonce=" + enc(nonce)
                + "&prompt=select_account";

        ResponseCookie bind = ResponseCookie.from(BIND_COOKIE, browserBinding)
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .path("/")
                .maxAge(STATE_TTL)
                .sameSite("Lax")
                .build();
        ResponseCookie nextHint = ResponseCookie.from(NEXT_COOKIE, row.getNextPath())
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .path("/")
                .maxAge(STATE_TTL)
                .sameSite("Lax")
                .build();
        var response = ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, bind.toString())
                .header(HttpHeaders.SET_COOKIE, nextHint.toString());
        String returnHost = sanitizeReturnHost(body.returnHost());
        if (returnHost != null) {
            response = response.header(
                    HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(RETURN_HOST_COOKIE, returnHost)
                            .httpOnly(true)
                            .secure(isSecureRequest(http))
                            .path("/")
                            .maxAge(STATE_TTL)
                            .sameSite("Lax")
                            .build()
                            .toString());
        }
        return response.body(new GoogleOAuthStartResponse(authorizeUrl));
    }

    /**
     * Legacy browser-facing callback: exchanges the code, sets cookies, and 302s to the
     * frontend handoff. Kept as a fallback for deployments where {@code /api/v1/*} is
     * fronted by a plain rewrite, or when the same-host BFF exchange route is unavailable.
     */
    @Transactional
    public ResponseEntity<Void> callback(HttpServletRequest http, String code, String state) {
        // Callback is always registered on the platform apex — never the tenant host.
        String frontendOrigin = apexOrigin();
        try {
            OauthFinish finish = complete(http, code, state);
            if (finish.shopPick() != null) {
                return shopPickRedirect(http, frontendOrigin, finish.shopPick());
            }
            Completed done = finish.session();
            String next = done.nextPath() != null ? done.nextPath() : "/";
            String handoff = frontendOrigin + "/auth/handoff?next=" + enc(next);
            if (done.slug() != null && !done.slug().isBlank()) {
                handoff += "&slug=" + enc(done.slug().trim());
            }
            if (done.returnHost() != null && !done.returnHost().isBlank()) {
                handoff += "&returnHost=" + enc(done.returnHost().trim());
            }

            HttpHeaders headers = new HttpHeaders();
            appendSessionCookies(headers, done.session());
            headers.add(HttpHeaders.SET_COOKIE, clearBindCookie(http));
            headers.add(HttpHeaders.SET_COOKIE, clearNextCookie(http));
            headers.add(HttpHeaders.SET_COOKIE, clearReturnHostCookie(http));
            headers.setLocation(URI.create(handoff));
            return new ResponseEntity<>(headers, HttpStatus.FOUND);
        } catch (Exception ex) {
            log.warn("Google OAuth callback failed: {}", ex.toString());
            return errorRedirect(frontendOrigin, oauthErrorCode(ex));
        }
    }

    /**
     * Same-host handoff for the Next.js BFF: exchange the code server-side and return the
     * session as a normal 200 JSON plus the refresh cookie. The BFF mints
     * {@code ub.access}/{@code ub.refresh} on the browser-facing host, so the flow does not
     * depend on a proxied 302 preserving {@code Set-Cookie}.
     */
    @Transactional
    public ResponseEntity<GoogleOAuthExchangeResponse> exchange(
            HttpServletRequest http, String code, String state) {
        OauthFinish finish;
        try {
            finish = complete(http, code, state);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Google OAuth exchange failed: {}", ex.toString());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed");
        }
        if (finish.shopPick() != null) {
            return shopPickExchange(http, finish.shopPick());
        }
        Completed done = finish.session();

        HttpHeaders headers = new HttpHeaders();
        appendSessionCookies(headers, done.session());
        headers.add(HttpHeaders.SET_COOKIE, clearBindCookie(http));
        headers.add(HttpHeaders.SET_COOKIE, clearNextCookie(http));
        headers.add(HttpHeaders.SET_COOKIE, clearReturnHostCookie(http));
        return ResponseEntity.ok()
                .headers(headers)
                .body(new GoogleOAuthExchangeResponse(
                        done.session().accessToken(),
                        done.nextPath(),
                        done.slug(),
                        done.returnHost()));
    }

    private record Completed(
            LoginResponse session, String nextPath, String slug, String returnHost) {}

    private record PendingShopPick(String token, String email, String nextPath, String returnHost) {}

    private record OauthFinish(Completed session, PendingShopPick shopPick) {}

    private record ShopPickIdentity(String email, String subject, String name, String returnHost) {}

    /** Shared code path for {@link #callback} and {@link #exchange}. */
    private OauthFinish complete(HttpServletRequest http, String code, String state) {
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw oauthError("missing_code", HttpStatus.BAD_REQUEST);
        }
        var google = platformIntegrationSettingsService.resolveGoogleOauth();
        if (!google.ready()) {
            throw oauthError("disabled", HttpStatus.SERVICE_UNAVAILABLE);
        }

        OAuthLoginState row = oauthLoginStateRepository
                .findById(TokenHasher.sha256Hex(state))
                .orElse(null);
        if (row == null) {
            throw oauthError("invalid_state", HttpStatus.BAD_REQUEST);
        }
        if (row.getConsumedAt() != null || Instant.now().isAfter(row.getExpiresAt())) {
            throw oauthError("expired_state", HttpStatus.BAD_REQUEST);
        }
        String binding = readCookie(http, BIND_COOKIE);
        if (binding == null || !binding.equals(row.getBrowserBinding())) {
            throw oauthError("binding_mismatch", HttpStatus.BAD_REQUEST);
        }
        row.setConsumedAt(Instant.now());
        oauthLoginStateRepository.save(row);

        String redirectUri = row.getRedirectUri();
        if (redirectUri == null || redirectUri.isBlank()) {
            redirectUri = resolveRedirectUri();
        }
        JsonNode tokenJson = exchangeCode(google, code, row.getCodeVerifier(), redirectUri);
        String idToken = text(tokenJson, "id_token");
        if (idToken == null) {
            throw oauthError("token_exchange", HttpStatus.BAD_GATEWAY);
        }
        JsonNode claims = parseIdTokenPayload(idToken);
        if (!validateClaims(claims, google.clientId(), row.getNonce())) {
            throw oauthError("invalid_token", HttpStatus.BAD_REQUEST);
        }
        if (!claims.path("email_verified").asBoolean(false)) {
            throw oauthError("email_unverified", HttpStatus.BAD_REQUEST);
        }
        String email = normaliseEmail(text(claims, "email"));
        String subject = text(claims, "sub");
        String name = firstNonBlank(text(claims, "name"), email);
        if (email == null || subject == null) {
            throw oauthError("missing_email", HttpStatus.BAD_REQUEST);
        }

        ResolvedLogin login = chooseLogin(http, row, email, subject, name);
        if (login.shopPick() != null) {
            return new OauthFinish(null, login.shopPick());
        }

        String businessId = login.businessId();
        User user = findOrCreateUser(row, businessId, email, subject, name);
        LoginResponse session = authService.issueSessionForUser(user, http, "google");
        String next = row.getNextPath() != null ? row.getNextPath() : "/";
        String slug = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getSlug)
                .orElse(null);
        return new OauthFinish(
                new Completed(session, next, slug, decodeReturnHost(row.getOnboardDraftJson())),
                null);
    }

    /**
     * Shops still available for a Google sign-in that paused on several staff
     * memberships. The pick cookie and browser binding must both still match.
     */
    @Transactional(readOnly = true)
    public GoogleOAuthShopChoiceResponse shops(HttpServletRequest http) {
        OAuthLoginState row = requireShopPick(http);
        ShopPickIdentity identity = readShopPickDraft(row.getOnboardDraftJson());
        return new GoogleOAuthShopChoiceResponse(identity.email(), staffShops(identity.email()));
    }

    /** Issues the Google session for the shop the person picked. */
    @Transactional
    public ResponseEntity<GoogleOAuthExchangeResponse> completeShop(
            HttpServletRequest http, String slug) {
        OAuthLoginState row = requireShopPick(http);
        ShopPickIdentity identity = readShopPickDraft(row.getOnboardDraftJson());
        String normalized = normalizeShopSlug(slug);
        if (normalized == null || !staffShopSlugs(identity.email()).contains(normalized)) {
            throw oauthError("unknown_shop", HttpStatus.BAD_REQUEST);
        }
        String businessId = businessIdForSlug(normalized);
        TenantRequestIds.bindBusinessId(http, businessId);
        row.setConsumedAt(Instant.now());
        row.setBusinessId(businessId);
        oauthLoginStateRepository.save(row);

        User user = findOrCreateUser(
                row, businessId, identity.email(), identity.subject(), identity.name());
        LoginResponse session = authService.issueSessionForUser(user, http, "google");
        String slugOut = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getSlug)
                .orElse(normalized);
        return sessionResponse(http, session, row.getNextPath(), slugOut, identity.returnHost(), true);
    }

    private void appendSessionCookies(HttpHeaders headers, LoginResponse session) {
        if (refreshTokenCookieSupport.isEnabled() && session.refreshToken() != null) {
            headers.addAll(refreshTokenCookieSupport.cookieHeaders(session.refreshToken()));
        }
    }

    private String clearBindCookie(HttpServletRequest http) {
        return ResponseCookie.from(BIND_COOKIE, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .sameSite("Lax")
                .build()
                .toString();
    }

    private String clearNextCookie(HttpServletRequest http) {
        return ResponseCookie.from(NEXT_COOKIE, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .sameSite("Lax")
                .build()
                .toString();
    }

    private String clearReturnHostCookie(HttpServletRequest http) {
        return ResponseCookie.from(RETURN_HOST_COOKIE, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .sameSite("Lax")
                .build()
                .toString();
    }

    private static ResponseStatusException oauthError(String code, HttpStatus status) {
        return new ResponseStatusException(status, code);
    }

    private static String oauthErrorCode(Exception ex) {
        if (ex instanceof ResponseStatusException rse
                && rse.getReason() != null
                && !rse.getReason().isBlank()) {
            return rse.getReason();
        }
        return "failed";
    }

    private record ResolvedLogin(String businessId, PendingShopPick shopPick) {
        static ResolvedLogin business(String id) {
            return new ResolvedLogin(id, null);
        }

        static ResolvedLogin pick(PendingShopPick pick) {
            return new ResolvedLogin(null, pick);
        }
    }

    /**
     * One staff shop signs in immediately. Several staff shops pause for a picker
     * instead of the dead-end {@code multi_shop} error password login already avoids.
     */
    private ResolvedLogin chooseLogin(
            HttpServletRequest http, OAuthLoginState row, String email, String subject, String name) {
        try {
            return ResolvedLogin.business(resolveBusinessId(http, row, email));
        } catch (ResponseStatusException ex) {
            if (!isMultiShop(ex)) {
                if (OAuthLoginState.INTENT_SIGN_IN.equals(row.getIntent())) {
                    throw oauthError("no_account", HttpStatus.UNAUTHORIZED);
                }
                throw oauthError("no_business", HttpStatus.BAD_REQUEST);
            }
            List<PublicSignInDestinationResponse> staff = staffShops(email);
            if (staff.size() > 1) {
                return ResolvedLogin.pick(saveShopPick(row, email, subject, name));
            }
            if (staff.size() == 1) {
                String businessId = businessIdForSlug(staff.get(0).slug());
                TenantRequestIds.bindBusinessId(http, businessId);
                return ResolvedLogin.business(businessId);
            }
            throw oauthError("multi_shop", HttpStatus.BAD_REQUEST);
        }
    }

    private PendingShopPick saveShopPick(
            OAuthLoginState source, String email, String subject, String name) {
        String token = randomUrlSafe(32);
        String returnHost = decodeReturnHost(source.getOnboardDraftJson());
        OAuthLoginState pick = new OAuthLoginState();
        pick.setStateHash(TokenHasher.sha256Hex(token));
        pick.setCodeVerifier("shop-pick");
        pick.setIntent(OAuthLoginState.INTENT_SHOP_PICK);
        pick.setNextPath(source.getNextPath());
        pick.setBrowserBinding(source.getBrowserBinding());
        pick.setNonce(randomUrlSafe(16));
        pick.setRedirectUri(firstNonBlank(source.getRedirectUri(), resolveRedirectUri()));
        pick.setExpiresAt(Instant.now().plus(STATE_TTL));
        pick.setOnboardDraftJson(writeShopPickDraft(email, subject, name, returnHost));
        oauthLoginStateRepository.save(pick);
        return new PendingShopPick(token, email, source.getNextPath(), returnHost);
    }

    private OAuthLoginState requireShopPick(HttpServletRequest http) {
        String token = readCookie(http, SHOP_PICK_COOKIE);
        String binding = readCookie(http, BIND_COOKIE);
        if (token == null || token.isBlank() || binding == null || binding.isBlank()) {
            throw oauthError("expired_state", HttpStatus.BAD_REQUEST);
        }
        OAuthLoginState row = oauthLoginStateRepository
                .findById(TokenHasher.sha256Hex(token))
                .orElse(null);
        if (row == null || !OAuthLoginState.INTENT_SHOP_PICK.equals(row.getIntent())) {
            throw oauthError("invalid_state", HttpStatus.BAD_REQUEST);
        }
        if (row.getConsumedAt() != null || Instant.now().isAfter(row.getExpiresAt())) {
            throw oauthError("expired_state", HttpStatus.BAD_REQUEST);
        }
        if (!binding.equals(row.getBrowserBinding())) {
            throw oauthError("binding_mismatch", HttpStatus.BAD_REQUEST);
        }
        return row;
    }

    private List<PublicSignInDestinationResponse> staffShops(String email) {
        return publicSignInDestinationService.byEmail(email).stream()
                .filter(row -> PublicSignInDestinationResponse.DOOR_STAFF.equals(row.door()))
                .filter(row -> row.slug() != null && !row.slug().isBlank())
                .toList();
    }

    private Set<String> staffShopSlugs(String email) {
        return staffShops(email).stream()
                .map(PublicSignInDestinationResponse::slug)
                .map(slug -> slug.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private String businessIdForSlug(String slug) {
        String normalized = normalizeShopSlug(slug);
        if (normalized == null) {
            throw oauthError("unknown_shop", HttpStatus.BAD_REQUEST);
        }
        return businessRepository.findBySlugAndDeletedAtIsNull(normalized)
                .map(Business::getId)
                .orElseThrow(() -> oauthError("unknown_shop", HttpStatus.BAD_REQUEST));
    }

    private ResponseEntity<Void> shopPickRedirect(
            HttpServletRequest http, String frontendOrigin, PendingShopPick pick) {
        StringBuilder target = new StringBuilder(frontendOrigin)
                .append("/login/staff?mode=office&googleShopPick=1");
        if (pick.nextPath() != null && !pick.nextPath().isBlank() && !"/".equals(pick.nextPath())) {
            target.append("&next=").append(enc(pick.nextPath().trim()));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, shopPickCookie(http, pick.token(), STATE_TTL));
        headers.setLocation(URI.create(target.toString()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseEntity<GoogleOAuthExchangeResponse> shopPickExchange(
            HttpServletRequest http, PendingShopPick pick) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, shopPickCookie(http, pick.token(), STATE_TTL));
        return ResponseEntity.ok()
                .headers(headers)
                .body(new GoogleOAuthExchangeResponse(
                        null,
                        pick.nextPath(),
                        null,
                        pick.returnHost(),
                        GoogleOAuthExchangeResponse.STATUS_CHOOSE_SHOP,
                        pick.email()));
    }

    private ResponseEntity<GoogleOAuthExchangeResponse> sessionResponse(
            HttpServletRequest http,
            LoginResponse session,
            String nextPath,
            String slug,
            String returnHost,
            boolean clearShopPick) {
        HttpHeaders headers = new HttpHeaders();
        appendSessionCookies(headers, session);
        headers.add(HttpHeaders.SET_COOKIE, clearBindCookie(http));
        if (clearShopPick) {
            headers.add(HttpHeaders.SET_COOKIE, clearShopPickCookie(http));
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(new GoogleOAuthExchangeResponse(session.accessToken(), nextPath, slug, returnHost));
    }

    private String shopPickCookie(HttpServletRequest http, String token, Duration maxAge) {
        return ResponseCookie.from(SHOP_PICK_COOKIE, token)
                .httpOnly(true)
                .secure(isSecureRequest(http))
                .path("/")
                .maxAge(maxAge)
                .sameSite("Lax")
                .build()
                .toString();
    }

    private String clearShopPickCookie(HttpServletRequest http) {
        return shopPickCookie(http, "", Duration.ZERO);
    }

    private String writeShopPickDraft(String email, String subject, String name, String returnHost) {
        try {
            var draft = new java.util.LinkedHashMap<String, String>();
            draft.put("email", email);
            draft.put("subject", subject);
            draft.put("name", name == null ? "" : name);
            if (returnHost != null && !returnHost.isBlank()) {
                draft.put("returnHost", returnHost);
            }
            return objectMapper.writeValueAsString(draft);
        } catch (Exception ex) {
            throw oauthError("failed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ShopPickIdentity readShopPickDraft(String json) {
        if (json == null || json.isBlank()) {
            throw oauthError("invalid_state", HttpStatus.BAD_REQUEST);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String email = normaliseEmail(text(node, "email"));
            String subject = text(node, "subject");
            if (email == null || subject == null) {
                throw oauthError("invalid_state", HttpStatus.BAD_REQUEST);
            }
            return new ShopPickIdentity(
                    email, subject, text(node, "name"), sanitizeReturnHost(text(node, "returnHost")));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw oauthError("invalid_state", HttpStatus.BAD_REQUEST);
        }
    }

    private static String normalizeShopSlug(String slug) {
        if (slug == null) {
            return null;
        }
        String normalized = slug.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 191 || !SHOP_SLUG.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private static boolean isMultiShop(ResponseStatusException ex) {
        return HttpStatus.BAD_REQUEST.equals(ex.getStatusCode())
                && AuthService.MULTI_SHOP_LOGIN_DETAIL.equals(ex.getReason());
    }

    private String resolveBusinessId(HttpServletRequest http, OAuthLoginState row, String email) {
        if (row.getBusinessId() != null && !row.getBusinessId().isBlank()) {
            String id = row.getBusinessId().trim();
            TenantRequestIds.bindBusinessId(http, id);
            return id;
        }
        return authService.resolveLoginBusinessId(http, email);
    }

    private User findOrCreateUser(
            OAuthLoginState row, String businessId, String email, String subject, String name) {
        var bySubject = userOAuthIdentityRepository.findByProviderAndProviderSubjectAndBusinessId(
                UserOAuthIdentity.PROVIDER_GOOGLE, subject, businessId);
        if (bySubject.isPresent()) {
            return userRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(bySubject.get().getUserId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account missing"));
        }

        var existing = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email);
        if (existing.isPresent()) {
            User user = existing.get();
            linkGoogle(user, subject, email);
            if (user.statusAsEnum() == UserStatus.INVITED) {
                user.setStatus(UserStatus.ACTIVE);
                userRepository.save(user);
            }
            return user;
        }

        if (OAuthLoginState.INTENT_SIGN_IN.equals(row.getIntent())
                || OAuthLoginState.INTENT_SHOP_PICK.equals(row.getIntent())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No shop for this Google account");
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName(name == null || name.isBlank() ? email : name.trim());
        user.setPasswordHash(passwordEncoder.encode(generateSentinelPassword()));
        user.setStatus(UserStatus.ACTIVE);
        user.setRoleId(resolveSignupRole(businessId).getId());
        user.setFailedAttempts(0);
        user.setAuthHourFailures(0);
        user = userRepository.save(user);
        linkGoogle(user, subject, email);
        return user;
    }

    private void linkGoogle(User user, String subject, String email) {
        if (userOAuthIdentityRepository
                .findByUserIdAndProvider(user.getId(), UserOAuthIdentity.PROVIDER_GOOGLE)
                .isPresent()) {
            return;
        }
        UserOAuthIdentity link = new UserOAuthIdentity();
        link.setId(UUID.randomUUID().toString());
        link.setBusinessId(user.getBusinessId());
        link.setUserId(user.getId());
        link.setProvider(UserOAuthIdentity.PROVIDER_GOOGLE);
        link.setProviderSubject(subject);
        link.setEmailAtLink(email);
        link.setCreatedAt(Instant.now());
        link.setUpdatedAt(Instant.now());
        userOAuthIdentityRepository.save(link);
    }

    private Role resolveSignupRole(String businessId) {
        if (userRepository.countByBusinessIdAndDeletedAtIsNull(businessId) == 0) {
            return roleRepository
                    .findSystemRoleByKey(IdentityService.OWNER_ROLE_KEY)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Owner role missing"));
        }
        return roleRepository
                .findSystemRoleByKey(signupRoleKey)
                .or(() -> roleRepository.findSystemRoleByKey("buyer"))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Signup role missing"));
    }

    private JsonNode exchangeCode(
            PlatformIntegrationSettingsService.ResolvedGoogleOauthConfig google,
            String code,
            String codeVerifier,
            String redirectUri
    ) {
        HttpResponse<String> response = Unirest.post(GOOGLE_TOKEN)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .field("grant_type", "authorization_code")
                .field("code", code)
                .field("redirect_uri", redirectUri)
                .field("client_id", google.clientId())
                .field("client_secret", google.clientSecret())
                .field("code_verifier", codeVerifier)
                .asString();
        if (!response.isSuccess()) {
            log.warn("Google token exchange HTTP {}", response.getStatus());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google token exchange failed");
        }
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google token parse failed");
        }
    }

    private JsonNode parseIdTokenPayload(String idToken) {
        // Payload-only decode: id_token arrives from Google's token endpoint over TLS in the
        // same request as the auth code exchange, so we trust transport + claim checks
        // (iss/aud/exp/nonce) rather than verifying the JWT signature locally.
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed id_token");
        }
        byte[] json = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed id_token payload");
        }
    }

    private boolean validateClaims(JsonNode claims, String clientId, String expectedNonce) {
        String aud = text(claims, "aud");
        String iss = text(claims, "iss");
        String nonce = text(claims, "nonce");
        long exp = claims.path("exp").asLong(0);
        boolean issOk = "accounts.google.com".equals(iss) || "https://accounts.google.com".equals(iss);
        boolean audOk = clientId != null && clientId.equals(aud);
        boolean nonceOk = expectedNonce != null && expectedNonce.equals(nonce);
        boolean expOk = exp > Instant.now().getEpochSecond();
        return issOk && audOk && nonceOk && expOk;
    }

    private ResponseEntity<Void> errorRedirect(String frontendOrigin, String code) {
        return errorRedirect(frontendOrigin, code, null, null);
    }

    private ResponseEntity<Void> errorRedirect(
            String frontendOrigin, String code, OAuthLoginState row) {
        return errorRedirect(
                frontendOrigin,
                code,
                row == null ? null : row.getNextPath(),
                row == null ? null : row.getBusinessId());
    }

    /**
     * Office / hub destinations return to staff office login so Google-only
     * owners see errors on the same page they started from.
     */
    private ResponseEntity<Void> errorRedirect(
            String frontendOrigin, String code, String nextPath, String businessId) {
        String target;
        if (preferOfficeLoginError(nextPath, businessId)) {
            StringBuilder sb = new StringBuilder(frontendOrigin)
                    .append("/login/staff?mode=office&googleError=")
                    .append(enc(code));
            if (nextPath != null && !nextPath.isBlank()) {
                sb.append("&next=").append(enc(nextPath.trim()));
            }
            target = sb.toString();
        } else {
            target = frontendOrigin + "/login?googleError=" + enc(code);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    private static boolean preferOfficeLoginError(String nextPath, String businessId) {
        if (businessId != null && !businessId.isBlank()) {
            return true;
        }
        if (nextPath == null || nextPath.isBlank()) {
            return false;
        }
        String p = nextPath.trim();
        if (!p.startsWith("/") || p.startsWith("//")) {
            return false;
        }
        if ("/".equals(p) || p.startsWith("/shop")) {
            return false;
        }
        return p.startsWith("/business")
                || p.startsWith("/overview")
                || p.startsWith("/settings")
                || p.startsWith("/inventory")
                || p.startsWith("/suppliers")
                || p.startsWith("/users")
                || p.startsWith("/branches")
                || p.startsWith("/reports")
                || p.startsWith("/cashier")
                || p.startsWith("/grocery")
                || p.startsWith("/butcher");
    }

    /**
     * Google redirect URI is platform-apex only ({@code kiosk.ke} / localhost).
     * Tenant hosts and custom domains (e.g. {@code palmart.co.ke}) must bounce to
     * apex to start OAuth so this URI and the bind cookie stay aligned.
     */
    private String resolveRedirectUri() {
        return apexOrigin() + "/api/v1/auth/oauth/google/callback";
    }

    private String apexOrigin() {
        String base = frontendBaseUrl == null ? "http://localhost:3000" : frontendBaseUrl.trim();
        return base.replaceAll("/$", "");
    }

    private static String sanitizeNext(String next) {
        if (next == null || next.isBlank()) {
            return "/";
        }
        String t = next.trim();
        if (!t.startsWith("/") || t.startsWith("//") || t.contains("://")) {
            return "/";
        }
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    /** Hostname only — no scheme/path. Used to return shoppers to custom domains. */
    private static String sanitizeReturnHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String t = host.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("http://") || t.startsWith("https://")) {
            try {
                t = URI.create(t).getHost();
            } catch (Exception ex) {
                return null;
            }
        }
        if (t == null || t.isBlank()) {
            return null;
        }
        int slash = t.indexOf('/');
        if (slash >= 0) {
            t = t.substring(0, slash);
        }
        int colon = t.indexOf(':');
        if (colon > 0) {
            t = t.substring(0, colon);
        }
        if (t.isBlank() || t.contains(" ") || t.contains("..")) {
            return null;
        }
        return t.length() > 253 ? null : t;
    }

    private String encodeReturnHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("returnHost", host));
        } catch (Exception ex) {
            return null;
        }
    }

    private String decodeReturnHost(String draftJson) {
        if (draftJson == null || draftJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(draftJson);
            return sanitizeReturnHost(text(node, "returnHost"));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String readCookie(HttpServletRequest http, String name) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    private static boolean isSecureRequest(HttpServletRequest http) {
        String proto = http.getHeader("X-Forwarded-Proto");
        if (proto != null && proto.toLowerCase(Locale.ROOT).contains("https")) {
            return true;
        }
        return http.isSecure();
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String generateSentinelPassword() {
        SecureRandom random = new SecureRandom();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String padBase64(String value) {
        int mod = value.length() % 4;
        if (mod == 0) {
            return value;
        }
        return value + "=".repeat(4 - mod);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
