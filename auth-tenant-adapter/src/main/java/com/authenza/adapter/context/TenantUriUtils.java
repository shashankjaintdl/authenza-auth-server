package com.authenza.adapter.context;

/**
 * Utility for resolving tenant identifiers from HTTP request URIs.
 */
public final class TenantUriUtils {

    private TenantUriUtils() {
        // Utility class
    }

    /**
     * Resolves the tenant identifier from the first non-empty path segment of a URI.
     * <p>Example: {@code "/system-admin/login"} &rarr; {@code "system-admin"}</p>
     *
     * @param uri the request URI
     * @return the resolved tenantId, or {@code null} if no valid tenant segment exists
     */
    public static String resolveTenantFromUri(String uri) {
        if (uri == null || uri.equals("/")) {
            return null;
        }
        String[] parts = uri.split("/");
        for (String part : parts) {
            if (!part.isEmpty()) {
                return part;
            }
        }
        return null;
    }
}
