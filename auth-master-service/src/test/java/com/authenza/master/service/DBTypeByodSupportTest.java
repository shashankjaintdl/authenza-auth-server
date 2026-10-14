package com.authenza.master.service;

import com.authenza.common.enums.DBType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DBTypeByodSupportTest {

    @Test
    void supportedEnginesMatchGaAllowlist() {
        assertTrue(DBType.MYSQL.isByodSupported());
        assertTrue(DBType.POSTGRES_SQL.isByodSupported());
        assertTrue(DBType.MARIA_DB.isByodSupported());
    }

    @Test
    void unsupportedEnginesRemainRejectedForByod() {
        assertFalse(DBType.ORACLE.isByodSupported());
        assertFalse(DBType.MICROSOFT_SQL.isByodSupported());
        assertFalse(DBType.IBM_DB2.isByodSupported());
    }

    @Test
    void jsonAliasesParseToSupportedTypes() {
        assertTrue(DBType.parse("POSTGRES").isByodSupported());
        assertTrue(DBType.parse("MARIADB").isByodSupported());
        assertTrue(DBType.parse("mysql").isByodSupported());
    }
}
