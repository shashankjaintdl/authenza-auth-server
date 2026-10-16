package com.authenza.e2e;

import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.FormData;
import org.junit.jupiter.api.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tenant Onboarding and Session Specifications")
public class TenantOnboardingE2ETest extends BaseE2ETest {

    @Nested
    @DisplayName("Given a newly provisioned tenant")
    class GivenNewTenant {

        private final String tenantId = "system-admin";
        private final String ownerEmail = "admin@authenza.io";

        @Test
        @DisplayName("When logging in with valid credentials, then redirect to callback succeeds")
        void verifyTenantLoginFlow() {
            // 1. Trigger API Request to check service status
            var statusResponse = apiRequest.get("/");
            assertTrue(
                    statusResponse.status() == 200 || statusResponse.status() == 302 || statusResponse.status() == 404,
                    "Auth Server is unreachable");

            // 2. Open standard OIDC Authorize endpoint to trigger login redirection
            String authUrl = "/system-admin/oauth2/authorize" +
                    "?response_type=code" +
                    "&client_id=super-admin-client" +
                    "&scope=openid%20profile%20system:admin" +
                    "&redirect_uri=http://localhost:4200" +
                    "&state=e2e_test_state";
            page.navigate(authUrl);
            System.out.println("E2E DEBUG: Navigated to " + page.url());
            System.out.println("E2E DEBUG: Page Title: " + page.title());

            // 3. Perform login actions
            page.getByLabel("Username").fill(ownerEmail);
            page.getByLabel("Password").fill("Admin@12345");
            page.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions().setName("Login"))
                    .click();

            // 4. Verify we redirect properly (expecting redirect or URL matching depending on setup)
            assertTrue(page.url().contains("code="), 
                    "Browser redirection flow failed: " + page.url());

            // 5. Extract Authorization Code to verify login success
            String url = page.url();
            
            // Extract the 'code' parameter value
            String code = url.substring(url.indexOf("code=") + 5);
            if (code.contains("&")) {
                code = code.substring(0, code.indexOf("&"));
            }
            
            assertFalse(code.isEmpty(), "Authorization code is empty");
            System.out.println("E2E SUCCESS: Successfully logged in and received Auth Code: " + code);

            // 6. Perform back-channel Token Exchange directly from the browser context (so it shows in Trace Viewer)
            Object tokenResponse = page.evaluate("async ({ code }) => {\n" +
                    "  const params = new URLSearchParams();\n" +
                    "  params.append('grant_type', 'authorization_code');\n" +
                    "  params.append('code', code);\n" +
                    "  params.append('redirect_uri', 'http://localhost:4200');\n" +
                    "\n" +
                    "  const res = await fetch('http://localhost:8081/system-admin/oauth2/token', {\n" +
                    "    method: 'POST',\n" +
                    "    headers: {\n" +
                    "      'Content-Type': 'application/x-www-form-urlencoded',\n" +
                    "      'Authorization': 'Basic c3VwZXItYWRtaW4tY2xpZW50OnN1cGVyLWFkbWluLXNlY3JldA=='\n" +
                    "    },\n" +
                    "    body: params\n" +
                    "  });\n" +
                    "  return res.ok ? await res.json() : { error: res.status, text: await res.text() };\n" +
                    "}", Map.of("code", code));

            System.out.println("E2E DEBUG: Token Response from Page Fetch: " + tokenResponse);
            
            // Assert that we received the access token payload
            assertTrue(tokenResponse.toString().contains("access_token"), "Token response does not contain access_token: " + tokenResponse);
        }
    }
}
