package com.alejandro.mtogateway.configuration.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Comprueba que el token venía dirigido a esta API. Sin esta validación basta con tener cuenta en el
 * realm para entrar: el token que Keycloak emite para el frontal de otra aplicación, o para una
 * cuenta de servicio ajena, está igual de bien firmado y lleva el mismo emisor.
 *
 * <p>Keycloak no incluye el {@code client_id} del resource server en {@code aud} por su cuenta; hace
 * falta un <em>audience mapper</em> en el cliente que pide el token.</p>
 *
 * <p>Es una copia literal de la clase homónima de {@code mto-configuration} y {@code mto-stock}: los
 * tres servicios validan la audiencia igual, y una variante propia solo serviría para que un día
 * dejaran de hacerlo.</p>
 */
public class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;

    public JwtAudienceValidator(String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()) {
            throw new IllegalArgumentException("La audiencia requerida no puede estar vacía");
        }

        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();

        if (audience != null && audience.contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The required audience is missing: " + requiredAudience,
                null));
    }
}
