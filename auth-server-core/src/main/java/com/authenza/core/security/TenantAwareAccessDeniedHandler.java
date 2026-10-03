package com.authenza.core.security;

import com.authenza.adapter.context.TenantContextHolder;
import com.authenza.adapter.context.TenantUriUtils;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Global access denied handler that ensures 403 Forbidden responses
 * (such as CSRF rejections on session expiry or Spring Boot ErrorController paths)
 * redirect the user cleanly back to their tenant-specific login page
 * instead of showing the Whitelabel error page.
 *
 * <p>Preserves the {@code tx} token from the request so the OAuth2 context survives
 * the redirect and is present when credentials are re-submitted.
 */
public final class TenantAwareAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = TenantUriUtils.resolveTenantFromUri(request.getRequestURI());
        }

        String base = (tenantId != null && !tenantId.isBlank())
                ? "/" + tenantId + "/login?session_expired"
                : "/login?session_expired";

        // Preserve the tx token from the POST body so the OAuth2 context
        // survives the CSRF rejection redirect and is present when the user
        // re-submits their credentials on the reloaded login page.
        String tx = request.getParameter("tx");
        String txParam = (tx != null && !tx.isBlank())
                ? "&tx=" + URLEncoder.encode(tx, StandardCharsets.UTF_8)
                : "";

        log.debug("[AccessDenied] Redirecting to {} due to: {}", base, accessDeniedException.getMessage());
        response.sendRedirect(base + txParam);
    }
}
