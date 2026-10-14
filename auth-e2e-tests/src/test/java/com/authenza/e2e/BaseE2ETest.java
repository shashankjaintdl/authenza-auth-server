package com.authenza.e2e;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import java.nio.file.Paths;

public abstract class BaseE2ETest {
    protected static Playwright playwright;
    protected static Browser browser;
    
    protected BrowserContext context;
    protected Page page;
    protected APIRequestContext apiRequest;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void createContext() {
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:8081"));
        
        // Start tracing screenshots/actions in background
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));

        page = context.newPage();
        apiRequest = playwright.request().newContext(new APIRequest.NewContextOptions()
                .setBaseURL("http://localhost:8081"));
    }

    @AfterEach
    void closeContext(TestInfo testInfo) {
        if (context != null) {
            // Save the visual browser trace ZIP on execution failures
            context.tracing().stop(new Tracing.StopOptions()
                    .setPath(Paths.get("build/reports/tests/traces/" + 
                            testInfo.getDisplayName().replace(" ", "_") + "-trace.zip")));
            context.close();
        }
        if (apiRequest != null) apiRequest.dispose();
    }
}
