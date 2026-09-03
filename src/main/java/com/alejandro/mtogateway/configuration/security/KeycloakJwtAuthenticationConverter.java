package com.alejandro.mtogateway.configuration.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Traduce los <i>claims</i> de un token de Keycloak a autoridades de Spring Security.
 *
 * <p>Es una versión reducida de la de {@code mto-configuration}: el gateway solo necesita roles para
 * cerrar su propio Actuator, así que no extrae <i>scopes</i> ni los permisos de autorización fina
 * de Keycloak. Sí conserva la parte que importa para la seguridad, la separación entre roles de
 * realm y roles de cliente.</p>
 *
 * <p>Los roles de realm se emiten <b>solo</b> con el prefijo {@code ROLE_REALM_}, nunca con
 * {@code ROLE_} a secas. Emitir ambos haría que un rol de realm y uno de cliente que se llamaran
 * igual acabaran en la misma autoridad, y quien tuviera el de realm pasaría una comprobación
 * pensada para el de cliente. Como quien administra el realm no es necesariamente quien escribe el
 * código, la coincidencia de nombres no es una hipótesis remota.</p>
 */
@Component
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String REALM_ROLE_PREFIX = "ROLE_REALM_";
    private static final String CLIENT_ROLE_PREFIX = "ROLE_CLIENT_";

    private final SecurityProperties securityProperties;

    public KeycloakJwtAuthenticationConverter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.addAll(extractRealmRoles(jwt));
        authorities.addAll(extractClientRoles(jwt, securityProperties.clientId()));

        return new JwtAuthenticationToken(jwt, authorities, resolvePrincipalName(jwt));
    }

    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        return rolesOf(jwt.getClaimAsMap(REALM_ACCESS)).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(REALM_ROLE_PREFIX + normalize(role)))
                .toList();
    }

    private Collection<GrantedAuthority> extractClientRoles(Jwt jwt, String clientId) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS);
        if (resourceAccess == null || clientId == null || clientId.isBlank()) {
            return Set.of();
        }

        if (!(resourceAccess.get(clientId) instanceof Map<?, ?> clientAccess)) {
            return Set.of();
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : rolesOf(clientAccess)) {
            // Las dos formas: la corta es la que usa hasRole(...) en la cadena de filtros, y la
            // larga permite distinguir explícitamente un rol de cliente cuando haga falta.
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + normalize(role)));
            authorities.add(new SimpleGrantedAuthority(CLIENT_ROLE_PREFIX + normalize(role)));
        }
        return authorities;
    }

    private Set<String> rolesOf(Map<?, ?> accessMap) {
        if (accessMap == null || !(accessMap.get(ROLES) instanceof Collection<?> roles)) {
            return Set.of();
        }

        Set<String> values = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String name && !name.isBlank()) {
                values.add(name);
            }
        }
        return values;
    }

    /**
     * El nombre del principal es lo que acaba en los logs del gateway. Si el <i>claim</i>
     * configurado no viene en el token se cae al {@code sub}, que Keycloak siempre emite: quedarse
     * sin nombre dejaría las líneas de log sin el dato que las hace útiles.
     */
    private String resolvePrincipalName(Jwt jwt) {
        String principal = jwt.getClaimAsString(securityProperties.principalClaim());
        return (principal != null && !principal.isBlank()) ? principal : jwt.getSubject();
    }

    /**
     * Permite nombrar los roles en Keycloak como es costumbre allí —minúsculas y guiones,
     * {@code ops-metrics}— y comprobarlos en el código como {@code OPS_METRICS}. Evítense los dos
     * puntos como separador: sobreviven a la normalización y obligarían a escribir
     * {@code hasRole("OPS:METRICS")}.
     */
    private String normalize(String value) {
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase();
    }
}
