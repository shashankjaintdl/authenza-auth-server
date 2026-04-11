-- ═══════════════════════════════════════════════════════════════
-- V0.0.3: IAM Tables — Dynamic RBAC (User, Role, Authority)
-- Generated from entity classes in auth-common module
-- This script runs inside EACH TENANT database
-- ═══════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────
-- 1. application_user (OIDC-compliant)
-- ──────────────────────────────────────────────
CREATE TABLE application_user (
    id                      BIGINT          AUTO_INCREMENT PRIMARY KEY,

    -- OIDC Standard Claims (Section 5.1)
    preferred_username      VARCHAR(150)    NOT NULL UNIQUE,
    name                    VARCHAR(255),
    given_name              VARCHAR(100),
    family_name             VARCHAR(100),
    email                   VARCHAR(255)    NOT NULL UNIQUE,
    email_verified          BOOLEAN         DEFAULT FALSE,
    phone_number            VARCHAR(20),
    phone_number_verified   BOOLEAN         DEFAULT FALSE,
    picture                 VARCHAR(500),
    locale                  VARCHAR(10),
    zoneinfo                VARCHAR(50),
    gender                  VARCHAR(20),
    birthdate               VARCHAR(10),     -- OIDC spec: "YYYY-MM-DD"

    -- Internal Authentication (NOT exposed as OIDC claims)
    password                VARCHAR(255)    NOT NULL,
    status                  VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts   INT             DEFAULT 0,
    locked_until            TIMESTAMP       NULL,
    password_changed_at     TIMESTAMP       NULL,
    mfa_enabled             BOOLEAN         DEFAULT FALSE,

    -- Audit
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_login_at           TIMESTAMP       NULL,
    last_login_ip           VARCHAR(45)
);

-- ──────────────────────────────────────────────
-- 2. authority (Granular Permission)
-- ──────────────────────────────────────────────
CREATE TABLE authority (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    permission      VARCHAR(100)    NOT NULL UNIQUE,   -- e.g., "user:read"
    display_name    VARCHAR(150),
    description     VARCHAR(500),
    category        VARCHAR(100),                      -- e.g., "User Management"
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ──────────────────────────────────────────────
-- 3. role (Group of Authorities)
-- ──────────────────────────────────────────────
CREATE TABLE role (
    id              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL UNIQUE,    -- e.g., "ADMIN"
    display_name    VARCHAR(150),
    description     VARCHAR(500),
    system_default  BOOLEAN         DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ──────────────────────────────────────────────
-- 4. role_authority (Role ↔ Authority ManyToMany)
-- ──────────────────────────────────────────────
CREATE TABLE role_authority (
    role_id         BIGINT NOT NULL,
    authority_id    BIGINT NOT NULL,
    PRIMARY KEY (role_id, authority_id),
    CONSTRAINT fk_role_authority_role
        FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_authority_authority
        FOREIGN KEY (authority_id) REFERENCES authority(id) ON DELETE CASCADE
);

-- ──────────────────────────────────────────────
-- 5. user_role (User ↔ Role ManyToMany)
-- ──────────────────────────────────────────────
CREATE TABLE user_role (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user
        FOREIGN KEY (user_id) REFERENCES application_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role
        FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE
);

-- ──────────────────────────────────────────────
-- 6. Performance Indexes
-- ──────────────────────────────────────────────
CREATE INDEX idx_user_email              ON application_user(email);
CREATE INDEX idx_user_preferred_username ON application_user(preferred_username);
CREATE INDEX idx_user_phone_number       ON application_user(phone_number);
CREATE INDEX idx_user_status             ON application_user(status);
CREATE INDEX idx_authority_permission    ON authority(permission);
CREATE INDEX idx_authority_category      ON authority(category);
CREATE INDEX idx_role_name               ON role(name);
