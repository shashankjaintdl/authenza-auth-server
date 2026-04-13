# Authenza - Comprehensive Testing Guide

This document outlines the complete testing strategy for the Authenza Multi-Tenant IAM Platform, covering all three layers of the testing pyramid.

---

## Testing Pyramid Overview

```
        ┌──────────────┐
        │   E2E Tests   │  ← Few, slow, high-confidence (auth-e2e-tests)
        │ REST Assured   │
        ├──────────────┤
        │ Integration    │  ← Medium count, real DB/Redis (per-module)
        │ Tests          │
        │ @SpringBootTest│
        │ + Testcontainers│
        ├──────────────┤
        │  Unit Tests    │  ← Many, fast, isolated (per-module)
        │  JUnit 5 +     │
        │  Mockito       │
        └──────────────┘
```

| Layer | Tool | Speed | What It Proves |
|---|---|---|---|
| **Unit** | JUnit 5 + Mockito | < 1 sec each | Individual class logic is correct |
| **Integration** | `@SpringBootTest` + Testcontainers | 5-15 sec each | Service boots, DB queries work, Redis publishes |
| **E2E** | REST Assured (`auth-e2e-tests`) | 30-60 sec total | Full cross-service flows work end-to-end |

---

## 1. Unit Tests (Per Module)

Unit tests live inside each individual module (e.g., `auth-iam-service/src/test/java/...`).

### What to Unit Test

| Module | Classes to Test | Example Assertion |
|---|---|---|
| `auth-iam-service` | `UserService` | Verify `registerUser()` encodes password before saving |
| `auth-iam-service` | `RestExceptionHandler` | Verify `ResourceAlreadyExistsException` returns 409 Conflict |
| `auth-notification-service` | `TemplateRenderingService` | Verify fallback branding is used when DB is empty |
| `auth-notification-service` | `DynamicMailSenderFactory` | Verify correct `JavaMailSender` is built from `SmtpSettings` |
| `auth-common` | `ApiResponse` | Verify all factory methods return correct status codes |
| `auth-tenant-adapter` | `TenantContextHolder` | Verify ThreadLocal set/get/clear cycle |

### Example: Unit Test for ApiResponse

```java
@Test
void testBadRequestFactory() {
    ApiResponse<Object> response = ApiResponse.badRequest("Invalid email");
    
    assertEquals(400, response.getStatus());
    assertFalse(response.isSuccess());
    assertEquals("Invalid email", response.getMessage());
    assertNull(response.getData());
}
```

### Example: Unit Test for UserService

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private NotificationEventPublisher eventPublisher;

    @InjectMocks private UserService userService;

    @Test
    void registerUser_shouldEncodePassword() throws Exception {
        // Arrange
        TenantContextHolder.setTenantId("test-tenant");
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("test@example.com");
        request.setPreferredUsername("testuser");
        request.setGivenName("Test");
        request.setFamilyName("User");
        request.setPassword("plaintext123");

        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByPreferredUsername(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode("plaintext123")).thenReturn("$2a$encoded");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        userService.registerUser(request);

        // Assert
        verify(passwordEncoder).encode("plaintext123");
        verify(userRepository).save(argThat(user -> 
            user.getPassword().equals("$2a$encoded")
        ));
    }

    @Test
    void registerUser_duplicateEmail_shouldThrow409() {
        TenantContextHolder.setTenantId("test-tenant");
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("existing@example.com");

        when(userRepository.findByEmail("existing@example.com"))
            .thenReturn(Optional.of(new User()));

        assertThrows(ResourceAlreadyExistsException.class, 
            () -> userService.registerUser(request));
    }
}
```

### Running Unit Tests

```bash
# Run unit tests for a specific module
./gradlew :auth-iam-service:test

# Run unit tests for all modules
./gradlew test
```

---

## 2. Integration Tests (Per Module with Testcontainers)

Integration tests verify that a single service correctly interacts with real databases and Redis.

### Dependencies Required

Add to each module's `build.gradle`:
```gradle
testImplementation 'org.testcontainers:mysql:1.19.8'
testImplementation 'org.testcontainers:junit-jupiter:1.19.8'
testImplementation 'com.redis:testcontainers-redis:2.2.2'
```

### Example: Integration Test for UserService + Real MySQL

```java
@SpringBootTest
@Testcontainers
class UserServiceIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("tenant_test")
            .withUsername("root")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.master.jdbc-url", mysql::getJdbcUrl);
        registry.add("app.datasource.master.username", mysql::getUsername);
        registry.add("app.datasource.master.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void registerUser_shouldPersistToDatabase() throws Exception {
        TenantContextHolder.setTenantId("system-master");

        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("integration@test.com");
        request.setPreferredUsername("integrationuser");
        request.setGivenName("Integration");
        request.setFamilyName("Test");
        request.setPassword("SecurePassword123!");

        userService.registerUser(request);

        Optional<User> saved = userRepository.findByEmail("integration@test.com");
        assertTrue(saved.isPresent());
        assertEquals("PENDING_VERIFICATION", saved.get().getStatus().name());
    }
}
```

### Running Integration Tests

```bash
# Docker must be running for Testcontainers!
./gradlew :auth-iam-service:test --tests "*IntegrationTest"
```

---

## 3. End-to-End Tests (`auth-e2e-tests` Module)

E2E tests treat the entire system as a black box. All microservices must be running, and the tests hit their real HTTP endpoints.

### Module Setup

**`auth-e2e-tests/build.gradle`:**
```gradle
plugins {
    id 'java'
}

dependencies {
    testImplementation 'io.rest-assured:rest-assured:5.5.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.12.2'
    testImplementation 'com.fasterxml.jackson.core:jackson-databind:2.18.3'
    
    // Access your shared DTOs for type-safe assertions
    testImplementation project(':auth-common')
}

test {
    useJUnitPlatform()
    systemProperty 'iam.base.url', System.getProperty('iam.base.url', 'http://localhost:8081')
    systemProperty 'notification.base.url', System.getProperty('notification.base.url', 'http://localhost:8084/notification')
}
```

### Example: Full Registration Flow E2E Test

```java
package com.authenza.e2e;

import com.authenza.common.dto.ApiResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserRegistrationFlowTest {

    private static final String IAM_BASE = System.getProperty("iam.base.url", "http://localhost:8081");
    private static final String TENANT_ID = "system-master";

    @Test
    @Order(1)
    @DisplayName("POST /register → Should create user with PENDING_VERIFICATION status")
    void shouldRegisterNewUser() {
        String body = """
            {
                "preferredUsername": "e2euser",
                "email": "e2e@authenza.test",
                "givenName": "E2E",
                "familyName": "Tester",
                "password": "TestPassword123!"
            }
            """;

        given()
            .baseUri(IAM_BASE)
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/v1/users/register")
        .then()
            .statusCode(200)
            .body("success", is(true));
    }

    @Test
    @Order(2)
    @DisplayName("POST /register with duplicate email → Should return 409 Conflict")
    void shouldRejectDuplicateEmail() {
        String body = """
            {
                "preferredUsername": "e2euser2",
                "email": "e2e@authenza.test",
                "givenName": "E2E",
                "familyName": "Duplicate",
                "password": "TestPassword123!"
            }
            """;

        given()
            .baseUri(IAM_BASE)
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .post("/api/v1/users/register")
        .then()
            .statusCode(409)
            .body("success", is(false))
            .body("message", containsString("already"));
    }
}
```

### Example: Branding Settings API E2E Test

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrandingSettingsFlowTest {

    private static final String NOTIFICATION_BASE = System.getProperty(
        "notification.base.url", "http://localhost:8084/notification");
    private static final String TENANT_ID = "system-master";

    @Test
    @Order(1)
    @DisplayName("PUT /settings/branding → Should upsert branding")
    void shouldUpdateBranding() {
        String body = """
            {
                "companyName": "E2E Corp",
                "primaryColor": "#00ff00",
                "supportEmail": "support@e2e.test"
            }
            """;

        given()
            .baseUri(NOTIFICATION_BASE)
            .header("X-Tenant-Id", TENANT_ID)
            .contentType(ContentType.JSON)
            .body(body)
        .when()
            .put("/api/v1/settings/branding")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("data.companyName", equalTo("E2E Corp"));
    }

    @Test
    @Order(2)
    @DisplayName("GET /settings/branding → Should return saved branding")
    void shouldFetchBranding() {
        given()
            .baseUri(NOTIFICATION_BASE)
            .header("X-Tenant-Id", TENANT_ID)
        .when()
            .get("/api/v1/settings/branding")
        .then()
            .statusCode(200)
            .body("data.companyName", equalTo("E2E Corp"))
            .body("data.primaryColor", equalTo("#00ff00"));
    }

    @Test
    @Order(3)
    @DisplayName("DELETE /settings/branding → Should reset to defaults")
    void shouldDeleteBranding() {
        given()
            .baseUri(NOTIFICATION_BASE)
            .header("X-Tenant-Id", TENANT_ID)
        .when()
            .delete("/api/v1/settings/branding")
        .then()
            .statusCode(200)
            .body("success", is(true))
            .body("message", containsString("reset"));
    }
}
```

### Running E2E Tests

```bash
# Step 1: Start infrastructure
docker compose up redis -d

# Step 2: Start all services in separate terminals
./gradlew :auth-master-service:bootRun
./gradlew :auth-notification-service:bootRun
./gradlew :auth-iam-service:bootRun
./gradlew :auth-server-core:bootRun

# Step 3: Run E2E tests
./gradlew :auth-e2e-tests:test
```

---

## 4. Manual Verification Checklist

For flows that are difficult to fully automate (like checking Redis events or inspecting email templates), use this manual checklist:

### Registration Flow
- [ ] `POST /api/v1/users/register` returns `200` with `ApiResponse` wrapper
- [ ] User appears in MySQL `users` table with `status = PENDING_VERIFICATION`
- [ ] Token appears in `email_verification_tokens` table
- [ ] `auth-notification-service` console prints `"Received email verification event"`
- [ ] Redis channel `email-verification` received the event

### Branding Settings Flow
- [ ] `PUT /settings/branding` creates a new row in `tenant_branding_settings`
- [ ] `GET /settings/branding` returns the saved values
- [ ] `DELETE /settings/branding` removes the row and `GET` returns empty defaults

### SMTP Settings Flow
- [ ] `PUT /settings/smtp` saves SMTP credentials
- [ ] `GET /settings/smtp` does NOT return the `password` field (security check)
- [ ] `DELETE /settings/smtp` clears settings; system falls back to default mail sender

### Exception Handling
- [ ] Duplicate email registration returns `409 Conflict` with `ApiResponse` envelope
- [ ] Missing `X-Tenant-Id` header returns `400 Bad Request`
- [ ] Invalid tenant ID returns appropriate error

---

## 5. CI/CD Integration (Future)

When you are ready to automate testing in a CI pipeline (e.g., GitHub Actions):

```yaml
# .github/workflows/test.yml
name: Authenza Test Suite
on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./gradlew test -x :auth-e2e-tests:test

  integration-tests:
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: test
          MYSQL_DATABASE: auth-master
        ports: ['3306:3306']
      redis:
        image: redis:7-alpine
        ports: ['6379:6379']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: ./gradlew test --tests "*IntegrationTest"
```

---

*This testing guide is a living document. Update it as new modules and flows are added to the Authenza platform.*
