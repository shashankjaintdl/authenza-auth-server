package com.authenza.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TenantUserDetailsTest {

    @Test
    public void testTenantUserDetailsGettersAndEquals() {
        TenantUserDetails user1 = new TenantUserDetails(
                "villsyn",
                "admin@authenza.io",
                "password123",
                true,
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))
        );

        TenantUserDetails user2 = new TenantUserDetails(
                "villsyn",
                "admin@authenza.io",
                "password123",
                true,
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))
        );

        TenantUserDetails userOtherTenant = new TenantUserDetails(
                "system-admin",
                "admin@authenza.io",
                "password123",
                true,
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))
        );

        assertEquals("villsyn", user1.getTenantId());
        assertEquals("admin@authenza.io", user1.getUsername());
        assertEquals(user1, user2);
        assertNotEquals(user1, userOtherTenant);
        assertEquals(user1.hashCode(), user2.hashCode());
    }

    @Test
    public void testJacksonSerializationAndDeserialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClassLoader classLoader = getClass().getClassLoader();
        List<com.fasterxml.jackson.databind.Module> modules = SecurityJackson2Modules.getModules(classLoader);
        mapper.registerModules(modules);
        mapper.registerModule(new OAuth2AuthorizationServerJackson2Module());
        mapper.addMixIn(TenantUserDetails.class, TenantUserDetailsMixin.class);

        TenantUserDetails original = new TenantUserDetails(
                "villsyn",
                "admin@authenza.io",
                "secretHash",
                true,
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))
        );

        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        assertTrue(json.contains("villsyn"));
        assertTrue(json.contains("admin@authenza.io"));

        TenantUserDetails deserialized = mapper.readValue(json, TenantUserDetails.class);
        assertNotNull(deserialized);
        assertEquals("villsyn", deserialized.getTenantId());
        assertEquals("admin@authenza.io", deserialized.getUsername());
        assertTrue(deserialized.isEnabled());
        assertEquals(1, deserialized.getAuthorities().size());
        assertEquals("ROLE_TENANT_ADMIN", deserialized.getAuthorities().iterator().next().getAuthority());
    }
}
