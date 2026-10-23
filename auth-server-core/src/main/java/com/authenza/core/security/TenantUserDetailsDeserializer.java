package com.authenza.core.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Jackson deserializer for {@link TenantUserDetails}.
 */
public class TenantUserDetailsDeserializer extends JsonDeserializer<TenantUserDetails> {

    private static final TypeReference<Set<SimpleGrantedAuthority>> SIMPLE_GRANTED_AUTHORITY_SET =
            new TypeReference<>() {};

    @Override
    public TenantUserDetails deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) jp.getCodec();
        JsonNode jsonNode = mapper.readTree(jp);

        JsonNode authoritiesNode = jsonNode.get("authorities");
        Set<? extends GrantedAuthority> authorities;
        if (authoritiesNode != null) {
            authorities = mapper.convertValue(authoritiesNode, SIMPLE_GRANTED_AUTHORITY_SET);
        } else {
            authorities = Collections.emptySet();
        }

        JsonNode tenantIdNode = readJsonNode(jsonNode, "tenantId");
        String tenantId = tenantIdNode.asText("system-admin");

        JsonNode usernameNode = readJsonNode(jsonNode, "username");
        String username = usernameNode.asText("");

        JsonNode passwordNode = readJsonNode(jsonNode, "password");
        String password = passwordNode.asText("");

        JsonNode enabledNode = readJsonNode(jsonNode, "enabled");
        boolean enabled = enabledNode.asBoolean(true);

        JsonNode accountNonExpiredNode = readJsonNode(jsonNode, "accountNonExpired");
        boolean accountNonExpired = accountNonExpiredNode.asBoolean(true);

        JsonNode credentialsNonExpiredNode = readJsonNode(jsonNode, "credentialsNonExpired");
        boolean credentialsNonExpired = credentialsNonExpiredNode.asBoolean(true);

        JsonNode accountNonLockedNode = readJsonNode(jsonNode, "accountNonLocked");
        boolean accountNonLocked = accountNonLockedNode.asBoolean(true);

        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "system-admin";
        }

        TenantUserDetails result = new TenantUserDetails(
                tenantId,
                username,
                password,
                enabled,
                accountNonExpired,
                credentialsNonExpired,
                accountNonLocked,
                authorities
        );

        if (passwordNode.asText(null) == null) {
            result.eraseCredentials();
        }

        return result;
    }

    private JsonNode readJsonNode(JsonNode jsonNode, String field) {
        return jsonNode.has(field) ? jsonNode.get(field) : MissingNode.getInstance();
    }
}
