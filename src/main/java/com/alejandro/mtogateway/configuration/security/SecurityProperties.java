package com.alejandro.mtogateway.configuration.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * No existe un interruptor para apagar la seguridad, igual que en {@code mto-configuration} y
 * {@code mto-stock}: lo que cambia entre entornos son estas properties, no la existencia de la
 * cadena de filtros. Un flag de ese tipo tampoco desactivaría nada — con
 * {@code spring-boot-starter-security} en el classpath, quedarse sin {@code SecurityFilterChain}
 * propio devuelve el control a la cadena por defecto de Boot, con formulario de login, CSRF y
 * sesiones delante de una API.
 *
 * <p>Para probar el enrutado sin token están abiertos {@code /actuator/health}, {@code /actuator/info}
 * y sus equivalentes de cada servicio a través del gateway. Ver README.md.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotBlank String clientId,
        @NotBlank String principalClaim,
        boolean audienceValidationEnabled,
        String requiredAudience,
        @Valid Cors cors
) {

    /**
     * Sin esta comprobación, un {@code KEYCLOAK_AUDIENCE} vacío apagaría la validación de audiencia
     * en tiempo de petición y sin dejar rastro en el log. O se valida la audiencia, o se ha
     * desactivado de forma explícita.
     */
    @AssertTrue(message = "app.security.required-audience es obligatorio cuando "
            + "app.security.audience-validation-enabled es true")
    public boolean isAudienceConfigurationConsistent() {
        return !audienceValidationEnabled || (requiredAudience != null && !requiredAudience.isBlank());
    }

    /**
     * El gateway es stateless y se autentica con la cabecera {@code Authorization}, así que no hay
     * cookies que enviar y {@code allowCredentials} solo ampliaría la superficie. Con credenciales
     * activas, además, Spring rechaza el comodín en tiempo de ejecución, de modo que un
     * {@code allowed-origins: "*"} puesto para salir del paso rompe cada preflight en vez de aflojar
     * la política.
     */
    public record Cors(
            @NotEmpty List<String> allowedOrigins,
            @NotEmpty List<String> allowedMethods,
            @NotEmpty List<String> allowedHeaders,
            List<String> exposedHeaders,
            boolean allowCredentials,
            @PositiveOrZero long maxAge
    ) {

        @AssertTrue(message = "app.security.cors.allowed-origins no admite el comodín '*': "
                + "enumérense los orígenes reales de cada entorno")
        public boolean isOriginListExplicit() {
            return allowedOrigins == null || !allowedOrigins.contains("*");
        }

        /**
         * El comodín con credenciales es la combinación que el navegador rechaza en tiempo de
         * ejecución: dejarla pasar no aflojaría la política, rompería cada preflight y el fallo
         * aparecería en la consola del navegador, lejos de quien escribió la configuración.
         */
        @AssertTrue(message = "app.security.cors.allow-credentials exige orígenes concretos, "
                + "sin comodines")
        public boolean isCredentialPolicyConsistent() {
            return !allowCredentials
                    || allowedOrigins == null
                    || allowedOrigins.stream().noneMatch(origin -> origin.contains("*"));
        }
    }
}
