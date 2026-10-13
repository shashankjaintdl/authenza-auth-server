package com.authenza.common.enums;

import org.springframework.util.StringUtils;

public enum DBType {

    MYSQL("MYSQL", "com.mysql.cj.jdbc.Driver"),
    ORACLE("ORACLE", "oracle.jdbc.OracleDriver"),
    POSTGRES_SQL("POSTGRES", "org.postgresql.Driver"),
    MARIA_DB("MARIADB", "org.mariadb.jdbc.Driver"),
    MICROSOFT_SQL("MSSQL", "com.microsoft.sqlserver.jdbc.SQLServerDriver"),
    IBM_DB2("IBMDB2", "com.ibm.db2.jcc.DB2Driver");

    private final String value;
    private final String defaultDriver;

    DBType(String value, String defaultDriver) {
        this.value = value;
        this.defaultDriver = defaultDriver;
    }

    public String getValue() {
        return this.value;
    }

    public String getDefaultDriver() {
        return this.defaultDriver;
    }

    public static DBType parse(final String s) throws IllegalArgumentException {
        if (!StringUtils.hasText(s)) {
            throw new IllegalArgumentException("DBType value must not be null or empty");
        }

        String normalized = s.trim().toUpperCase().replace("-", "_").replace(" ", "_");

        for (DBType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }

        if (normalized.contains("POSTGRES")) {
            return POSTGRES_SQL;
        }
        if (normalized.contains("MARIA")) {
            return MARIA_DB;
        }
        if (normalized.contains("ORACLE")) {
            return ORACLE;
        }
        if (normalized.contains("SQLSERVER") || normalized.contains("MSSQL")) {
            return MICROSOFT_SQL;
        }
        if (normalized.contains("DB2")) {
            return IBM_DB2;
        }

        return MYSQL;
    }
}
