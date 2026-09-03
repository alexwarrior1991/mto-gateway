package com.alejandro.mtogateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El test que prueba de verdad lo que hace el gateway: que una llamada al prefijo público llega al
 * servicio con la ruta interna que ese servicio espera.
 *
 * <p>Comprobar el YAML enlazado no bastaría. Eso demostraría que la cadena
 * {@code RewritePath=...} está bien escrita, pero no que la expresión regular produce la ruta
 * correcta, ni que el escape de {@code $\{segment}} sobrevive a la carga del YAML, ni que la ruta de
 * Actuator gana la carrera de orden frente al comodín de la API — que son justo las tres cosas que
 * es más fácil que estén mal.</p>
 *
 * <p>El servicio de destino es un {@link HttpServer} del propio JDK ({@code com.sun.net.httpserver}),
 * de modo que esto no añade ni WireMock ni Testcontainers ni ninguna otra dependencia, y el test
 * corre sin Docker.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIntegrationTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final List<String> RECEIVED_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> RECEIVED_CORRELATION_IDS = new CopyOnWriteArrayList<>();
    private static final List<String> RECEIVED_AUTHORIZATION = new CopyOnWriteArrayList<>();

    private static final HttpServer DOWNSTREAM = startDownstream();

    /**
     * Cliente sin proxy explícitamente: en un entorno con {@code https.proxyHost} en las opciones de
     * la JVM, el selector por defecto podría intentar sacar por ahí una llamada a {@code localhost}.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    /**
     * Se arranca en un inicializador estático y no en un {@code @BeforeAll} porque
     * {@link DynamicPropertySource} se evalúa al cargar el contexto, y para entonces el puerto ya
     * tiene que existir.
     */
    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewayRoutingIntegrationTest::record);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        RECEIVED_PATHS.add(exchange.getRequestURI().toString());
        RECEIVED_CORRELATION_IDS.add(exchange.getRequestHeaders().getFirst(CORRELATION_HEADER));
        RECEIVED_AUTHORIZATION.add(exchange.getRequestHeaders().getFirst("Authorization"));

        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @DynamicPropertySource
    static void pointBothServicesAtTheStub(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("app.services.configuration.url", () -> uri);
        registry.add("app.services.stock.url", () -> uri);
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    /**
     * El gateway valida el token de verdad; lo que no hay en un test es un Keycloak que lo emita.
     * Sustituir el decodificador deja intacta toda la cadena de seguridad —el filtro del bearer, el
     * conversor de roles, las reglas de autorización— y solo cambia de dónde sale el JWT. No hay
     * ningún token real escrito en el código.
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @LocalServerPort
    private int gatewayPort;

    @BeforeEach
    void resetStubAndToken() {
        RECEIVED_PATHS.clear();
        RECEIVED_CORRELATION_IDS.clear();
        RECEIVED_AUTHORIZATION.clear();

        Jwt jwt = Jwt.withTokenValue("stub-token")
                .header("alg", "RS256")
                .claim("preferred_username", "tester")
                .claim("iss", "http://auth.mto.local:8082/realms/mto")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString())).thenReturn(jwt);
    }

    // ------------------------------------------------------------------ enrutado

    @Test
    void theConfigurationPrefixIsRewrittenToTheInternalV1Path() throws Exception {
        HttpResponse<String> response = authenticated("/api/configuration/profiles");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/configuration/profiles"), RECEIVED_PATHS);
    }

    @Test
    void theStockPrefixIsRewrittenToTheInternalInventoryPath() throws Exception {
        HttpResponse<String> response = authenticated("/api/stock/materials");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/inventory/materials"), RECEIVED_PATHS);
    }

    @Test
    void deepPathsAndTheQueryStringSurviveTheRewrite() throws Exception {
        authenticated("/api/stock/movements/42?page=0&size=20");

        assertEquals(List.of("/api/v1/inventory/movements/42?page=0&size=20"), RECEIVED_PATHS);
    }

    /**
     * El grupo de la expresión regular se pega al prefijo justamente para esto: si llevara la barra
     * fuera, la ruta sin sufijo se reescribiría con una barra final huérfana.
     */
    @Test
    void theBareServicePrefixDoesNotLeaveATrailingSlash() throws Exception {
        authenticated("/api/configuration");

        assertEquals(List.of("/api/v1/configuration"), RECEIVED_PATHS);
    }

    /**
     * La comprobación que justifica el {@code order} de las rutas. Si ganara el comodín de la API,
     * la salud del servicio se buscaría en {@code /api/v1/configuration/actuator/health}, que no
     * existe, y el síntoma sería un 404 imposible de atribuir.
     */
    @Test
    void theActuatorRouteWinsOverTheCatchAllApiRoute() throws Exception {
        HttpResponse<String> response = anonymous("/api/configuration/actuator/health");

        assertEquals(200, response.statusCode(), "La sonda de salud no exige token");
        assertEquals(List.of("/actuator/health"), RECEIVED_PATHS);
    }

    @Test
    void theStockActuatorRouteIsRewrittenTheSameWay() throws Exception {
        anonymous("/api/stock/actuator/health");

        assertEquals(List.of("/actuator/health"), RECEIVED_PATHS);
    }

    @Test
    void anUnknownServicePrefixIsNotRoutedAnywhere() throws Exception {
        HttpResponse<String> response = authenticated("/api/unknown/thing");

        assertEquals(404, response.statusCode());
        assertTrue(RECEIVED_PATHS.isEmpty(), "No se ha llamado a ningún servicio");
    }

    /**
     * La garantía de la que depende todo el reparto de responsabilidades: el gateway autentica, pero
     * la autorización fina la hace cada servicio, y para eso necesita el token del usuario tal cual.
     * Si el proxy borrara o reescribiera esta cabecera, cada llamada moriría con un 401 del servicio
     * después de haber pasado el gateway.
     */
    @Test
    void theAuthorizationHeaderReachesTheServiceUntouched() throws Exception {
        authenticated("/api/stock/materials");

        assertEquals(List.of("Bearer stub-token"), RECEIVED_AUTHORIZATION);
    }

    // ------------------------------------------------------------------ correlación

    @Test
    void aGeneratedCorrelationIdTravelsDownstreamAndComesBackInTheResponse() throws Exception {
        HttpResponse<String> response = anonymous("/api/stock/actuator/health");

        String returned = response.headers().firstValue(CORRELATION_HEADER).orElse(null);
        assertNotNull(returned);
        assertDoesNotThrow(() -> UUID.fromString(returned));
        assertEquals(List.of(returned), RECEIVED_CORRELATION_IDS,
                "El servicio de destino ha visto exactamente el mismo identificador");
    }

    @Test
    void anInboundCorrelationIdIsPreservedEndToEnd() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/actuator/health"))
                        .header(CORRELATION_HEADER, "probe-123")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals("probe-123", response.headers().firstValue(CORRELATION_HEADER).orElse(null));
        assertEquals(List.of("probe-123"), RECEIVED_CORRELATION_IDS);
    }

    @Test
    void anInboundCorrelationIdThatIsNotSafeIsReplacedBeforeItLeavesTheGateway() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/actuator/health"))
                        .header(CORRELATION_HEADER, "not a valid id")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        String returned = response.headers().firstValue(CORRELATION_HEADER).orElse(null);
        assertNotEquals("not a valid id", returned);
        assertDoesNotThrow(() -> UUID.fromString(returned));
        assertEquals(List.of(returned), RECEIVED_CORRELATION_IDS);
    }

    @Test
    void theGatewayOwnEndpointsAlsoCarryACorrelationId() throws Exception {
        HttpResponse<String> response = anonymous("/actuator/health");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue(CORRELATION_HEADER).isPresent(),
                "También las peticiones que no se enrutan a ningún servicio");
    }

    // ------------------------------------------------------------------ seguridad

    @Test
    void anApiCallWithoutATokenIsRejectedAtTheEdge() throws Exception {
        HttpResponse<String> response = anonymous("/api/stock/materials");

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"),
                "El cliente necesita saber que le toca traer un bearer");
        assertTrue(RECEIVED_PATHS.isEmpty(), "Se rechaza antes de gastar una conexión con el servicio");
    }

    @Test
    void theProblemDetailOfA401CarriesTheCorrelationId() throws Exception {
        HttpResponse<String> response = anonymous("/api/stock/materials");

        String correlationId = response.headers().firstValue(CORRELATION_HEADER).orElseThrow();
        assertTrue(response.body().contains(correlationId),
                "El identificador va también en el cuerpo: es lo único que hay que pedirle al cliente");
    }

    @Test
    void theGatewayOwnHealthIsOpenButItsMetricsAreNot() throws Exception {
        assertEquals(200, anonymous("/actuator/health").statusCode());
        assertEquals(401, anonymous("/actuator/prometheus").statusCode());
    }

    @Test
    void anAuthenticatedUserWithoutTheOperationRoleCannotReadTheMetrics() throws Exception {
        // El token del stub no trae resource_access, así que no hay ROLE_OPS_METRICS.
        assertEquals(403, authenticated("/actuator/prometheus").statusCode());
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void aPreflightFromAnAllowedOriginIsApprovedWithoutTouchingAnyService() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/materials"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:4200",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(RECEIVED_PATHS.isEmpty(), "El preflight se contesta en el gateway");
    }

    @Test
    void aPreflightFromAnUnknownOriginIsRejected() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/materials"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    /**
     * Sin exponerla, el navegador ve llegar la cabecera y no deja leerla desde JavaScript: el
     * identificador sería inútil justo para quien tiene que pegarlo en un informe de error.
     */
    @Test
    void theCorrelationHeaderIsExposedToTheBrowser() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/actuator/health"))
                        .header("Origin", "http://localhost:4200")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertTrue(response.headers().firstValue("Access-Control-Expose-Headers")
                        .orElse("").contains(CORRELATION_HEADER));
    }

    // ------------------------------------------------------------------ utilidades

    private URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private HttpResponse<String> anonymous(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(gatewayUri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authenticated(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(path))
                        .header("Authorization", "Bearer stub-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
