package com.authenza.master.model;

import jakarta.annotation.Generated;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "tenants")
public class Tenant{

    @Id
    private Long id;
    private String tenantId;        // e.g., "customer-a"
    private String dbType;        // MYSQL, POSTGRES, ORACLE
    private String jdbcUrl;
    private String username;
    private String encryptedPassword;
    private String driverClassName;
    private String active;

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

    public static Tenant create(String tenantId, String dbType, String jdbcUrl,
                                String username, String encryptedPassword, String driverClassName,
                                String active){
        Tenant tenant = new Tenant();
        tenant.setId(null);
        tenant.setTenantId(tenantId);
        tenant.setDbType(dbType);
        tenant.setJdbcUrl(jdbcUrl);
        tenant.setUsername(username);
        tenant.setEncryptedPassword(encryptedPassword);
        tenant.setDriverClassName(driverClassName);
        tenant.setActive(active);
        return tenant;
    }
}
