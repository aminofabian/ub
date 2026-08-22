package zelisline.ub.desktop.license;

/**
 * Source of the till's stable machine identity — the SHA-256 hex string the
 * vendor bakes into a license token ({@code --fingerprint}). A license only
 * activates on the machine whose fingerprint matches.
 */
public interface MachineFingerprintProvider {

    /** The till's machine fingerprint (SHA-256 hex digest), never null/blank. */
    String get();
}
