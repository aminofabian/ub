package zelisline.ub.platform.logs;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Stashes failure detail on the current request so
 * {@link PlatformRequestLogInterceptor} can persist it after the response is
 * written. {@code @ExceptionHandler} clears the servlet {@code Exception}
 * argument, so without this bridge failed rows only have a status code.
 */
public final class PlatformRequestLogErrorCapture {

    public static final String ATTR_TITLE = PlatformRequestLogErrorCapture.class.getName() + ".title";
    public static final String ATTR_DETAIL = PlatformRequestLogErrorCapture.class.getName() + ".detail";
    public static final String ATTR_TYPE = PlatformRequestLogErrorCapture.class.getName() + ".type";
    public static final String ATTR_EXCEPTION_CLASS =
            PlatformRequestLogErrorCapture.class.getName() + ".exceptionClass";
    public static final String ATTR_EXCEPTION_CHAIN =
            PlatformRequestLogErrorCapture.class.getName() + ".exceptionChain";
    public static final String ATTR_STACK = PlatformRequestLogErrorCapture.class.getName() + ".stack";
    public static final String ATTR_PROBLEM_JSON =
            PlatformRequestLogErrorCapture.class.getName() + ".problemJson";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DETAIL = 12_000;
    private static final int MAX_STACK = 16_000;
    private static final int MAX_CHAIN = 4_000;

    private PlatformRequestLogErrorCapture() {}

    public static void capture(ProblemDetail problem, Throwable ex) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return;
        }
        capture(servletAttrs.getRequest(), problem, ex);
    }

    public static void capture(HttpServletRequest request, ProblemDetail problem, Throwable ex) {
        if (request == null) {
            return;
        }
        if (problem != null) {
            if (problem.getTitle() != null) {
                request.setAttribute(ATTR_TITLE, clip(problem.getTitle(), 255));
            }
            if (problem.getDetail() != null) {
                request.setAttribute(ATTR_DETAIL, clip(problem.getDetail(), MAX_DETAIL));
            }
            if (problem.getType() != null) {
                request.setAttribute(ATTR_TYPE, clip(problem.getType().toString(), 255));
            }
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", problem.getType() == null ? null : problem.getType().toString());
                payload.put("title", problem.getTitle());
                payload.put("status", problem.getStatus());
                payload.put("detail", problem.getDetail());
                payload.put("instance", problem.getInstance() == null ? null : problem.getInstance().toString());
                if (problem.getProperties() != null && !problem.getProperties().isEmpty()) {
                    payload.put("properties", problem.getProperties());
                }
                request.setAttribute(ATTR_PROBLEM_JSON, clip(MAPPER.writeValueAsString(payload), MAX_DETAIL));
            } catch (Exception ignored) {
                // Best-effort — never break error handling for logging.
            }
        }
        if (ex != null) {
            request.setAttribute(ATTR_EXCEPTION_CLASS, clip(ex.getClass().getName(), 255));
            request.setAttribute(ATTR_EXCEPTION_CHAIN, clip(flattenChain(ex), MAX_CHAIN));
            request.setAttribute(ATTR_STACK, clip(stackSummary(ex), MAX_STACK));
            if (request.getAttribute(ATTR_TITLE) == null && ex.getMessage() != null) {
                request.setAttribute(ATTR_TITLE, clip(ex.getMessage(), 255));
            }
            if (request.getAttribute(ATTR_DETAIL) == null) {
                request.setAttribute(ATTR_DETAIL, clip(flattenChain(ex), MAX_DETAIL));
            }
        }
    }

    public static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    public static String flattenChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (sb.length() > 0) {
                sb.append("\n↳ ");
            }
            sb.append(cur.getClass().getName());
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    public static String stackSummary(Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        pw.flush();
        String full = sw.toString();
        // Keep the top of the stack — enough to diagnose without dumping megabytes.
        String[] lines = full.split("\n");
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            if (kept >= 80) {
                out.append("… (").append(lines.length - kept).append(" more lines)\n");
                break;
            }
            out.append(line).append('\n');
            kept++;
        }
        return out.toString();
    }
}
