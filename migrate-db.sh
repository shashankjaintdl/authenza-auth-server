#!/bin/bash

# Exit on any error
set -e

MYSQL_CMD="/usr/local/mysql/bin/mysql -u root -psjain@123"

# We already successfully imported system_admin. We just need to clean up auth-master.
echo "Cleaning up auth-master database (with FK checks disabled)..."
$MYSQL_CMD auth-master -e "
    SET FOREIGN_KEY_CHECKS = 0;
    DROP TABLE IF EXISTS application_user;
    DROP TABLE IF EXISTS authority;
    DROP TABLE IF EXISTS email_verification_token;
    DROP TABLE IF EXISTS oauth2_authorization;
    DROP TABLE IF EXISTS oauth2_authorization_consent;
    DROP TABLE IF EXISTS oauth2_registered_client;
    DROP TABLE IF EXISTS role;
    DROP TABLE IF EXISTS role_authority;
    DROP TABLE IF EXISTS tenant_branding_settings;
    DROP TABLE IF EXISTS tenant_settings;
    DROP TABLE IF EXISTS tenant_smtp_settings;
    DROP TABLE IF EXISTS user_role;
    DROP TABLE IF EXISTS user_session;
    DELETE FROM DATABASECHANGELOG WHERE FILENAME LIKE 'db/changelog/tenant%';
    SET FOREIGN_KEY_CHECKS = 1;
"

echo "Cleanup completed successfully!"
