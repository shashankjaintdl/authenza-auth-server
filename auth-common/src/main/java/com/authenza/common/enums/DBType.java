package com.authenza.common.enums;

import org.springframework.util.StringUtils;

public enum DBType {

    MYSQL("MYSQL"),
    ORACLE("ORACLE"),
    POSTGRES_SQL("POSTGRES"),
    MARIA_DB("MARIADB"),
    MICROSOFT_SQL("MSSQL"),
    IBM_DB2("IBMDB2");

    private final String value;

    DBType(String value){
        this.value = value;
    }

    public String getValue(){
        return this.value;
    }

    public static DBType parse(final String s) throws IllegalArgumentException{

        if (!StringUtils.hasText(s)){
            throw new IllegalArgumentException("DBType value must not be null or empty");
        }

        if (s.equals(DBType.ORACLE.getValue())){
            return ORACLE;
        }

        if (s.equals(DBType.POSTGRES_SQL.getValue())){
            return POSTGRES_SQL;
        }

        if (s.equals(DBType.MARIA_DB.getValue())){
            return MARIA_DB;
        }

        if (s.equals(DBType.MICROSOFT_SQL.getValue())){
            return MICROSOFT_SQL;
        }

        if (s.equals(DBType.IBM_DB2.getValue())){
            return IBM_DB2;
        }

        return MYSQL;

    }

}
