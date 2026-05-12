package com.authenza.master.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "tenants")
public class Tenant {

    @Id
    private Long id;
    private String tenantId; // e.g., "customer-a"
    private String dbType; // MYSQL, POSTGRES, ORACLE
    private String jdbcUrl;
    private String username;
    private String encryptedPassword;
    private String driverClassName;
    private String active;
    private String ownerId;
    private String accountType;   // PERSONAL | COMPANY
    private String orgName;       // null for PERSONAL accounts
    private String employeeRange; // null for PERSONAL accounts
    private boolean isDefault;    // Primary tenant for this user
    private java.time.Instant lastAccessedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String isActive() {
        return active;
    }

    public void setActive(String active) {
        this.active = active;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }

    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }

    public String getEmployeeRange() { return employeeRange; }
    public void setEmployeeRange(String employeeRange) { this.employeeRange = employeeRange; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public java.time.Instant getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(java.time.Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public static Tenant create(String tenantId, String dbType, String jdbcUrl,
            String username, String encryptedPassword, String driverClassName,
            String active, String ownerId, String accountType, String orgName, String employeeRange, boolean isDefault) {
        Tenant tenant = new Tenant();
        tenant.setId(null);
        tenant.setTenantId(tenantId);
        tenant.setDbType(dbType);
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setUsername(username);
        tenant.setEncryptedPassword(encryptedPassword);
        tenant.setDriverClassName(driverClassName);
        tenant.setActive(active);
        tenant.setOwnerId(ownerId);
        tenant.setAccountType(accountType);
        tenant.setOrgName(orgName);
        tenant.setEmployeeRange(employeeRange);
        tenant.setDefault(isDefault);
        tenant.setLastAccessedAt(java.time.Instant.now());
        return tenant;
    }
}
