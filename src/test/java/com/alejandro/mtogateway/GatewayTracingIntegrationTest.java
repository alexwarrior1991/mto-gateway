package com.alejandro.mtogateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El gateway como origen de la traza distribuida.
 *
 * <p>Va en su propio contexto y no en {@link GatewayRoutingIntegrationTest} por dos motivos que
 * obligan a properties distintas:</p>
 *
 * <ul>
 *   <li>{@code @AutoConfigureTracing}. Sin ella, Spring Boot aplica
 *       {@code management.tracing.export.enabled=false} a todo {@code @SpringBootTest}, y con el
 *       exportador apagado el <i>tracer</i> no graba: no se crea ningún span y por tanto no hay
 *       {@code traceparent} que inyectar. Comprobado — el mismo test sin la anotación ve la cabecera
 *       vacía mientras la aplicación real la manda perfectamente.</li>
 *   <li>Muestreo al 100%. Con el 0.1 de producción la mayoría de los spans no se muestrearían y
 *       estas aserciones serían intermitentes. El {@code traceparent} viaja igual sin muestrear —con
 *       el flag {@code 00}—, pero un test no debe depender de eso para pasar.</li>
 * </ul>
 *
 * <p>El endpoint de OTLP apunta al mismo servidor de prueba: así el exportador recibe un 200 en vez
 * de llenar la salida del test de avisos de conexión rechazada. Sus {@code POST} a
 * {@code /v1/traces} se descartan al registrar, para que no se cuelen en las aserciones.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureTracing
class GatewayTracingIntegrationTest {

    private static final List<String> RECEIVED_PATHS = new CopyOnWriteArrayList<>();

    /** La lista completa y no el primer valor: la pregunta es si llega una vez o dos. */
    private static final List<List<String>> RECEIVED_TRACEPARENTS = new CopyOnWriteArrayList<>();

    private static final HttpServer DOWNSTREAM = startDownstream();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewayTracingIntegrationTest::record);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // El propio exportador de spans usa este servidor. Sus envios no son trafico proxeado.
        if (!path.startsWith("/v1/traces")) {
            RECEIVED_PATHS.add(path);
            RECEIVED_TRACEPARENTS.add(List.copyOf(
                    exchange.getRequestHeaders().getOrDefault("traceparent", List.of())));
        }

        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @DynamicPropertySource
    static void pointEverythingAtTheStub(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("app.services.configuration.url", () -> uri);
        registry.add("app.services.stock.url", () -> uri);
        registry.add("management.otlp.tracing.endpoint", () -> uri + "/v1/traces");
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private Tracer tracer;

    @LocalServerPort
    private int gatewayPort;

    @BeforeEach
    void resetStubAndToken() {
        RECEIVED_PATHS.clear();
        RECEIVED_TRACEPARENTS.clear();

        Jwt jwt = Jwt.withTokenValue("stub-token")
                .header("alg", "RS256")
                .claim("preferred_username", "tester")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        org.mockito.Mockito.when(jwtDecoder.decode(org.mockito.ArgumentMatchers.anyString())).thenReturn(jwt);
    }

    /**
     * Que la dependencia esté de verdad. {@code spring-boot-starter-opentelemetry} se puede quitar
     * del {@code pom.xml} sin que nada deje de compilar, y el síntoma sería que el gateway desaparece
     * de las trazas en silencio.
     */
    @Test
    void theTracerIsWired() {
        assertNotNull(tracer);
    }

    /**
     * El gateway es el borde, o sea el sitio donde la traza distribuida tiene que <b>empezar</b>.
     * Sin esta cabecera cada servicio abriría su propia traza y no habría forma de ver una petición
     * de punta a punta.
     *
     * <p>Que llegue <b>una sola vez</b> no es una obviedad: el proxy copia todas las cabeceras
     * entrantes y además la instrumentación del {@code RestClient} inyecta la suya, así que dos
     * valores era un desenlace perfectamente posible — y dejaría al servicio de destino eligiendo
     * cuál respeta.</p>
     */
    @Test
    void theGatewayStartsATraceAndSendsExactlyOneTraceparent() throws Exception {
        authenticated("/api/stock/materials");

        assertEquals(List.of("/api/v1/inventory/materials"), RECEIVED_PATHS);
        assertEquals(1, RECEIVED_TRACEPARENTS.get(0).size(),
                "Un único traceparent aguas abajo. Recibido: " + RECEIVED_TRACEPARENTS.get(0));
        assertTrue(RECEIVED_TRACEPARENTS.get(0).get(0).startsWith("00-"),
                "Formato W3C. Recibido: " + RECEIVED_TRACEPARENTS.get(0).get(0));
    }

    /**
     * Si el cliente ya trae una traza, el gateway la continúa en vez de abrir una nueva: el
     * {@code trace-id} —los 32 caracteres de en medio— tiene que ser el mismo. El {@code span-id} sí
     * cambia, porque el salto por el gateway es un span hijo.
     */
    @Test
    void anInboundTraceIsContinuedAndNotRestarted() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";

        CLIENT.send(HttpRequest.newBuilder(gatewayUri("/api/stock/materials"))
                        .header("Authorization", "Bearer stub-token")
                        .header("traceparent", "00-" + traceId + "-b7ad6b7169203331-01")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        List<String> downstream = RECEIVED_TRACEPARENTS.get(0);
        assertEquals(1, downstream.size(), "Sigue siendo uno solo. Recibido: " + downstream);
        assertEquals(traceId, downstream.get(0).split("-")[1],
                "La misma traza, no una nueva. Recibido: " + downstream.get(0));
    }

    /**
     * Las dos identidades conviven. El {@code X-Correlation-Id} no se sustituye por la traza: es
     * legible, lo puede teclear una persona en una incidencia y sobrevive a que el trazado esté
     * apagado o el span no se muestree.
     */
    @Test
    void theCorrelationIdKeepsTravellingAlongsideTheTrace() throws Exception {
        HttpResponse<String> response = authenticated("/api/configuration/profiles");

        assertNotNull(response.headers().firstValue("X-Correlation-Id").orElse(null));
        assertEquals(1, RECEIVED_TRACEPARENTS.get(0).size());
    }

    private URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private HttpResponse<String> authenticated(String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(path))
                        .header("Authorization", "Bearer stub-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
