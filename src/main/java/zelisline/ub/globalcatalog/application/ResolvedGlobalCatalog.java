package zelisline.ub.globalcatalog.application;

import zelisline.ub.globalcatalog.domain.GlobalCatalog;

/**
 * Published catalog a tenant resolves to, plus how resolution chose it.
 */
public record ResolvedGlobalCatalog(GlobalCatalog catalog, String via) {
}
