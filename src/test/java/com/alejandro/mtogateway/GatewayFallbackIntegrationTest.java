package com.alejandro.mtogateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qué ve el cliente cuando el servicio de destino no está.
 *
 * <p>Va en su propio contexto porque necesita justo lo contrario que
 * {@link GatewayRoutingIntegrationTest}: un destino que no responde. Sin el filtro
 * {@code CircuitBreaker} y su fallback, esto sería un 500 con la traza del proxy dentro, y el
 * cliente no podría distinguir «he pedido algo mal» de «mto-stock está caído».</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 45s y no los 30s de produccion a proposito: el Retry-After se lee de la configuracion
        // real del circuito, asi que con un valor distinto del de application.yaml el test no puede
        // pasar por casualidad si alguien vuelve a escribir la cifra a mano.
        properties = "resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state=45s")
class GatewayFallbackIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    /** Un puerto que se reserva y se suelta acto seguido: nadie escucha ahí. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void pointStockAtNothing(DynamicPropertyRegistry registry) {
        String dead = "http://127.0.0.1:" + closedPort();
        registry.add("app.services.stock.url", () -> dead);
        registry.add("app.services.configuration.url", () -> dead);
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @LocalServerPort
    private int gatewayPort;

    @BeforeEach
    void stubTheToken() {
        Jwt jwt = Jwt.withTokenValue("stub-token")
                .header("alg", "RS256")
                .claim("preferred_username", "tester")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString())).thenReturn(jwt);
    }

    @Test
    void anUnreachableServiceBecomesA503AndNotA500() throws Exception {
        HttpResponse<String> response = call("/api/stock/materials");

        assertEquals(503, response.statusCode());
        assertEquals("45", response.headers().firstValue("Retry-After").orElse(null),
                "Derivado de wait-duration-in-open-state, no escrito a mano: reintentar antes de que "
                        + "el circuito se plantee cerrarse solo suma peticiones rechazadas");
    }

    @Test
    void theFallbackBodySaysWhichServiceIsDownAndCarriesTheCorrelationId() throws Exception {
        HttpResponse<String> response = call("/api/stock/materials");

        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/problem+json"));
        assertTrue(response.body().contains("mto-stock"), "El cuerpo nombra el servicio, no la ruta interna");

        String correlationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertTrue(response.body().contains(correlationId));
    }

    /**
     * Tras el {@code forward} interno, {@code getRequestURI()} sería {@code /fallback/stock}. Lo que
     * le sirve al cliente es la ruta que él pidió.
     */
    @Test
    void theFallbackReportsThePathTheClientActuallyAskedFor() throws Exception {
        HttpResponse<String> response = call("/api/stock/materials");

        assertTrue(response.body().contains("/api/stock/materials"),
                "En 'instance' va la ruta pública, no /fallback/stock. Cuerpo: " + response.body());
    }

    @Test
    void eachServiceGetsItsOwnFallback() throws Exception {
        assertTrue(call("/api/configuration/profiles").body().contains("mto-configuration"));
    }

    /**
     * Las rutas de Actuator no llevan circuit breaker a propósito, así que un servicio caído sale
     * por el despacho de error. Ese despacho pasa por la cadena de seguridad, y sin permitir
     * {@code /error} el cliente recibía un <b>401</b>: «tu token no vale» cuando el problema real
     * era que el servicio no respondía.
     */
    @Test
    void anErrorOnARouteWithoutACircuitBreakerIsNotDisguisedAsA401() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + gatewayPort + "/api/stock/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode(),
                "El error que se ve tiene que ser el que ha pasado, no uno de autenticación");
    }

    private HttpResponse<String> call(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                        .header("Authorization", "Bearer stub-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
