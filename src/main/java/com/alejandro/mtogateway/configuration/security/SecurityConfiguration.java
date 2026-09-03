package com.alejandro.mtogateway.configuration.security;

import com.alejandro.mtogateway.filter.CorrelationIdProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * El gateway es el <b>borde de autenticación</b>: comprueba que el token está firmado por el realm,
 * que lo emitió quien dice y que sigue vigente, y rechaza el tráfico anónimo antes de gastar una
 * conexión contra un servicio. La <b>autorización</b> —quién puede leer qué y quién puede borrar
 * qué— se queda donde vive el dato, en {@code mto-configuration} y {@code mto-stock}. Duplicar aquí
 * su matriz de roles crearía una segunda fuente de verdad que se desincronizaría con la primera ruta
 * nueva, y además haría falta tocar el gateway para cada permiso nuevo de cualquier servicio.
 *
 * <p>La cabecera {@code Authorization} llega intacta al servicio de destino: no es <i>hop-by-hop</i>,
 * así que el proxy la reenvía tal cual y ninguna ruta la borra. Tampoco se usa {@code TokenRelay},
 * que cambiaría el token del usuario por el del gateway: los dos servicios auditan a la persona, no
 * al proxy.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({SecurityProperties.class, CorrelationIdProperties.class})
public class SecurityConfiguration {

    /**
     * Salud e info de los servicios de detrás, a través de su prefijo público. El {@code *} de en
     * medio es un comodín de un solo segmento, de modo que un tercer servicio ferroviario queda
     * cubierto sin tocar esta clase.
     *
     * <p>No abre nada que estuviera cerrado: los dos servicios ya sirven {@code /actuator/health}
     * sin autenticar y con {@code show-details: when-authorized}, así que lo que se ve por aquí es
     * exactamente lo mismo que se ve llamándolos de frente. A cambio, comprobar que el enrutado
     * funciona no exige montar Keycloak.</p>
     */
    private static final String[] DOWNSTREAM_PROBES = {
            "/api/*/actuator/health",
            "/api/*/actuator/health/**",
            "/api/*/actuator/info"
    };

    private final SecurityProperties securityProperties;
    private final KeycloakJwtAuthenticationConverter jwtAuthenticationConverter;
    private final GatewayAuthenticationErrorHandler authenticationErrorHandler;

    public SecurityConfiguration(
            SecurityProperties securityProperties,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
            GatewayAuthenticationErrorHandler authenticationErrorHandler
    ) {
        this.securityProperties = securityProperties;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.authenticationErrorHandler = authenticationErrorHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No hay sesión ni cookies que proteger: la petición se autentica con la cabecera
                // Authorization y CSRF solo rompería cada POST que llegase.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationErrorHandler)
                        .accessDeniedHandler(authenticationErrorHandler))
                .authorizeHttpRequests(authorize -> {
                    // El preflight viaja sin Authorization por definición: si pidiera token, cada
                    // llamada desde un navegador fallaría antes de empezar.
                    authorize.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

                    // Salud e info del propio gateway, para el balanceador y las sondas.
                    authorize.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info"
                    ).permitAll();

                    authorize.requestMatchers(DOWNSTREAM_PROBES).permitAll();

                    // Los fallbacks se alcanzan por un forward interno, que no pasa por esta cadena,
                    // pero se declaran igualmente: si algún día se llega a ellos por una petición
                    // normal, un 401 taparía el 503 que explica qué servicio está caído.
                    authorize.requestMatchers("/fallback/**").permitAll();

                    // El despacho de error SÍ pasa por esta cadena (spring.security.filter
                    // .dispatcher-types incluye ERROR de serie). Sin esta línea, un fallo del proxy
                    // en una ruta sin circuit breaker salía como 401 en vez de como el error que
                    // era: el cliente veía «tu token no vale» cuando el problema era que el
                    // servicio estaba caído. No expone nada, porque server.error.include-message e
                    // include-stacktrace están en 'never'.
                    authorize.requestMatchers("/error").permitAll();

                    // Lo que modifica va antes que la regla general y con su propio permiso: en
                    // Actuator las @WriteOperation viajan por POST y las @DeleteOperation por
                    // DELETE. El resto de Actuator —prometheus incluido, que enumera las rutas
                    // internas— pide permiso de lectura de operación.
                    authorize.requestMatchers(HttpMethod.POST, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers(HttpMethod.DELETE, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers("/actuator/**").hasRole(SecurityRoles.OPS_METRICS);

                    // Todo lo demás —es decir, /api/**— exige un token válido y nada más. Ver el
                    // javadoc de la clase.
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    /**
     * El gateway en su sabor servlet no trae CORS propio: sus rutas son {@code RouterFunction} de
     * Spring MVC, así que lo que aplica es el CORS de Spring Web de siempre. Este bean lo recoge
     * {@code http.cors(...)} de arriba, de modo que hay un único sitio donde está escrita la
     * política.
     *
     * <p>La cabecera de correlación se añade aquí a las dos listas en lugar de enumerarse en el
     * YAML. El gateway <em>pone</em> esa cabecera en cada respuesta, así que tiene que aceptarla y
     * exponerla sea cual sea su nombre: es una invariante del código y no algo que se pueda borrar
     * de un fichero de configuración sin que nada se queje. Antes estaba escrita a mano en el YAML
     * y cambiar {@code app.correlation.header-name} dejaba al navegador sin poder leerla — sin
     * exponerla, el identificador es inútil justo para quien tiene que pegarlo en un informe de
     * error.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorrelationIdProperties correlationProperties) {
        SecurityProperties.Cors corsProperties = securityProperties.cors();
        String correlationHeader = correlationProperties.headerName();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(corsProperties.allowedMethods());
        configuration.setAllowedHeaders(withHeader(corsProperties.allowedHeaders(), correlationHeader));
        configuration.setExposedHeaders(withHeader(corsProperties.exposedHeaders(), correlationHeader));
        configuration.setAllowCredentials(corsProperties.allowCredentials());
        configuration.setMaxAge(corsProperties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Añade la cabecera a la lista si no está ya, comparando sin distinguir mayúsculas porque los
     * nombres de cabecera no las distinguen y {@code x-correlation-id} escrito en el YAML no debe
     * producir una entrada duplicada.
     *
     * <p>Con el comodín no se toca nada: {@code "*"} ya lo cubre todo, y añadir un nombre concreto
     * al lado convertiría la lista en una enumeración parcial que no es lo que se pidió.</p>
     */
    private static List<String> withHeader(List<String> configured, String header) {
        List<String> headers = (configured != null) ? configured : List.of();

        if (headers.contains(CorsConfiguration.ALL)
                || headers.stream().anyMatch(header::equalsIgnoreCase)) {
            return headers;
        }

        List<String> merged = new ArrayList<>(headers);
        merged.add(header);
        return merged;
    }

    /**
     * Se construye con el JWK Set en lugar de con el descubrimiento por emisor: {@code
     * JwtDecoders.fromIssuerLocation()} hace una llamada HTTP bloqueante al crear el bean, de modo
     * que el gateway no arrancaría si Keycloak todavía no sirve el {@code
     * .well-known/openid-configuration}. Con el JWK Set la descarga es perezosa —la primera vez que
     * llega un token— y un reinicio simultáneo de la plataforma deja de ser un fallo de arranque.
     * Misma decisión, y por el mismo motivo, que en {@code mto-configuration} y {@code mto-stock}.
     */
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwtProperties = properties.getJwt();
        String issuerUri = jwtProperties.getIssuerUri();

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withJwkSetUri(resolveJwkSetUri(jwtProperties))
                .build();

        // Se parte del validador por defecto en vez de reemplazarlo: incluye la comprobación de
        // emisor y de vigencia, y hereda las que Spring Security añada en versiones futuras.
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                audienceValidator()
        ));

        return jwtDecoder;
    }

    /**
     * Keycloak publica el JWK Set en una ruta fija bajo el realm. Se respeta {@code jwk-set-uri} si
     * está configurado, para no atar el gateway a esa convención.
     */
    static String resolveJwkSetUri(OAuth2ResourceServerProperties.Jwt jwtProperties) {
        if (StringUtils.hasText(jwtProperties.getJwkSetUri())) {
            return jwtProperties.getJwkSetUri();
        }

        return jwtProperties.getIssuerUri() + "/protocol/openid-connect/certs";
    }

    /**
     * En el gateway la validación de audiencia viene <b>desactivada</b> de serie, y es una decisión
     * y no un descuido: la audiencia es por servicio ({@code mto-configuration-api},
     * {@code mto-stock-api}) y cada uno valida la suya. Exigir aquí una audiencia propia obligaría a
     * declarar {@code mto-gateway-api} en el <i>audience mapper</i> de todos los clientes del realm,
     * y hasta que eso ocurra el gateway rechazaría tokens que los servicios sí aceptan.
     *
     * <p>Que {@code required-audience} esté relleno cuando la validación está activa lo garantiza
     * {@link SecurityProperties} en el arranque, así que aquí no hay ninguna rama que deje pasar el
     * token por falta de configuración.</p>
     */
    private OAuth2TokenValidator<Jwt> audienceValidator() {
        if (!securityProperties.audienceValidationEnabled()) {
            return jwt -> OAuth2TokenValidatorResult.success();
        }

        return new JwtAudienceValidator(securityProperties.requiredAudience());
    }
}
