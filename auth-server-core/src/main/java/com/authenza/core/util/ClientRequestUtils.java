package com.authenza.core.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared extraction of client IP and device labels from HTTP requests.
 * Used by session recording and adaptive risk evaluation to avoid duplication.
 */
public final class ClientRequestUtils {

    private ClientRequestUtils() {
    }

    /**
     * Returns the real client IP, preferring the first {@code X-Forwarded-For} hop.
     */
    public static String resolveIpAddress(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public static String resolveUserAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return request.getHeader("User-Agent");
    }

    public static String resolveSecChUa(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        return request.getHeader("Sec-CH-UA");
    }

    /**
     * Human-readable device label from User-Agent (e.g. "Chrome on macOS").
     */
    public static String parseDeviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown Device";
        }

        String browser;
        if (userAgent.contains("Edg/")) {
            browser = "Edge";
        } else if (userAgent.contains("OPR/") || userAgent.contains("Opera")) {
            browser = "Opera";
        } else if (userAgent.contains("Chrome")) {
            browser = "Chrome";
        } else if (userAgent.contains("Firefox")) {
            browser = "Firefox";
        } else if (userAgent.contains("Safari")) {
            browser = "Safari";
        } else if (userAgent.contains("curl")) {
            browser = "curl";
        } else if (userAgent.contains("PostmanRuntime")) {
            browser = "Postman";
        } else {
            browser = "Browser";
        }

        String os;
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            os = "iOS";
        } else if (userAgent.contains("Android")) {
            os = "Android";
        } else if (userAgent.contains("Windows")) {
            os = "Windows";
        } else if (userAgent.contains("Mac OS X")) {
            os = "macOS";
        } else if (userAgent.contains("Linux")) {
            os = "Linux";
        } else {
            os = "Unknown OS";
        }

        return browser + " on " + os;
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * Normalizes User-Agent for fingerprinting (collapse runs of whitespace).
     */
    public static String normalizeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "";
        }
        return userAgent.trim().replaceAll("\\s+", " ");
    }
}
