package com.alejandro.mtogateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
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

    /**
     * El único origen que admitía el CORS propio de cada servicio mientras lo tuvieron. El gateway
     * admite además el 5173, y esa diferencia es la que destapaba el CORS doble.
     */
    private static final String SERVICE_ALLOWED_ORIGIN = "http://localhost:4200";

    private static final List<String> RECEIVED_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> RECEIVED_CORRELATION_IDS = new CopyOnWriteArrayList<>();
    private static final List<String> RECEIVED_AUTHORIZATION = new CopyOnWriteArrayList<>();
    /** Todos los valores y no el primero: la pregunta es si llega alguno. */
    private static final List<String> RECEIVED_ORIGINS = new CopyOnWriteArrayList<>();
    /** Las cabeceras que dicen de dónde viene la llamada, tal como llegan al servicio: {@code nombre: valor}. */
    private static final List<String> RECEIVED_FORWARDED = new CopyOnWriteArrayList<>();

    /** Los cuerpos que llegan al servicio, uno por petición con cuerpo: lo que de verdad cruzó el gateway. */
    private static final List<ReceivedBody> RECEIVED_BODIES = new CopyOnWriteArrayList<>();

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MEGABYTE = 1024 * 1024;

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

    /**
     * Contesta como un servicio con su propio CORS registrado en {@code /**}, como lo tuvieron todos
     * y como lo traería uno nuevo: el {@code CorsFilter} de Spring pone los tres {@code Vary} en cada
     * respuesta, añade su {@code Access-Control-Allow-Origin} cuando le llega un {@code Origin} que
     * admite y contesta 403 cuando le llega uno que no. Sin imitarlo, los tests del CORS doble
     * pasarían también con el gateway reenviando {@code Origin}.
     *
     * <p>Y devuelve la cabecera de correlación que recibe, como mto-configuration, mto-users y
     * mto-notification.</p>
     */
    private static void record(HttpExchange exchange) throws IOException {
        RECEIVED_PATHS.add(exchange.getRequestURI().toString());
        RECEIVED_CORRELATION_IDS.add(exchange.getRequestHeaders().getFirst(CORRELATION_HEADER));
        RECEIVED_AUTHORIZATION.add(exchange.getRequestHeaders().getFirst("Authorization"));
        RECEIVED_ORIGINS.addAll(exchange.getRequestHeaders().getOrDefault("Origin", List.of()));
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lowerCaseName = name.toLowerCase(Locale.ROOT);
            if (lowerCaseName.equals("forwarded") || lowerCaseName.startsWith("x-forwarded-")
                    || lowerCaseName.equals("x-real-ip")) {
                values.forEach(value -> RECEIVED_FORWARDED.add(lowerCaseName + ": " + value));
            }
        });

        boolean hasBody = !"GET".equals(exchange.getRequestMethod()) && !"OPTIONS".equals(exchange.getRequestMethod());
        if (hasBody) {
            // Leído entero antes de contestar, como hace un servicio con un multipart: si el gateway
            // corta el cuerpo a medias, la lectura falla y aquí no se apunta nada.
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED_BODIES.add(new ReceivedBody(exchange.getRequestHeaders().getFirst("Content-Type"), body));
        }

        Headers responseHeaders = exchange.getResponseHeaders();
        String correlationId = exchange.getRequestHeaders().getFirst(CORRELATION_HEADER);
        if (correlationId != null) {
            responseHeaders.add(CORRELATION_HEADER, correlationId);
        }
        responseHeaders.add("Vary", "Origin");
        responseHeaders.add("Vary", "Access-Control-Request-Method");
        responseHeaders.add("Vary", "Access-Control-Request-Headers");

        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !SERVICE_ALLOWED_ORIGIN.equals(origin)) {
            respond(exchange, 403, "text/plain", "Invalid CORS request");
            return;
        }
        if (origin != null) {
            responseHeaders.add("Access-Control-Allow-Origin", origin);
        }
        if (hasBody && exchange.getRequestURI().getPath().endsWith("/jobs/import")) {
            // Lo que contesta mto-configuration al lanzar una importación: 202 con el trabajo.
            responseHeaders.add("Location", "/api/v1/configuration/profiles/jobs/42");
            respond(exchange, 202, "application/json", "{\"jobId\":42,\"status\":\"PENDING\"}");
            return;
        }
        respond(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String content)
            throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @DynamicPropertySource
    static void pointBothServicesAtTheStub(DynamicPropertyRegistry registry) {
        String uri = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("app.services.configuration.url", () -> uri);
        registry.add("app.services.stock.url", () -> uri);
        registry.add("app.services.maintenance.url", () -> uri);
        registry.add("app.services.users.url", () -> uri);
        registry.add("app.services.notification.url", () -> uri);
        registry.add("app.services.resources.url", () -> uri);
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
        RECEIVED_ORIGINS.clear();
        RECEIVED_FORWARDED.clear();
        RECEIVED_BODIES.clear();

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
    void theMaintenancePrefixIsRewrittenToTheVersionedInternalPath() throws Exception {
        HttpResponse<String> response = authenticated("/api/maintenance/orders?status=PLANNED");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/maintenance/orders?status=PLANNED"), RECEIVED_PATHS);
    }

    @Test
    void theMaintenanceActuatorRouteWinsOverItsApiRoute() throws Exception {
        HttpResponse<String> response = anonymous("/api/maintenance/actuator/health");

        assertEquals(200, response.statusCode(), "La sonda de salud no exige token");
        assertEquals(List.of("/actuator/health"), RECEIVED_PATHS);
    }

    @Test
    void theUsersPrefixIsRewrittenToTheVersionedInternalPath() throws Exception {
        HttpResponse<String> response = authenticated("/api/users/profiles/mto-users-admin");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/users/profiles/mto-users-admin"), RECEIVED_PATHS);
    }

    @Test
    void theUsersActuatorRouteWinsOverItsApiRoute() throws Exception {
        HttpResponse<String> response = anonymous("/api/users/actuator/health");

        assertEquals(200, response.statusCode(), "La sonda de salud no exige token");
        assertEquals(List.of("/actuator/health"), RECEIVED_PATHS);
    }

    @Test
    void theNotificationsPrefixIsRewrittenToTheVersionedInternalPath() throws Exception {
        HttpResponse<String> response = authenticated("/api/notifications/inbox?unread=true");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/notifications/inbox?unread=true"), RECEIVED_PATHS);
    }

    @Test
    void theNotificationsActuatorRouteWinsOverItsApiRoute() throws Exception {
        HttpResponse<String> response = anonymous("/api/notifications/actuator/health");

        assertEquals(200, response.statusCode(), "La sonda de salud no exige token");
        assertEquals(List.of("/actuator/health"), RECEIVED_PATHS);
    }

    @Test
    void theResourcesPrefixIsRewrittenToTheVersionedInternalPath() throws Exception {
        HttpResponse<String> response = authenticated("/api/resources/vehicles/positions?executionPackageId=14");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/resources/vehicles/positions?executionPackageId=14"), RECEIVED_PATHS);
    }

    @Test
    void theResourcesActuatorRouteWinsOverItsApiRoute() throws Exception {
        HttpResponse<String> response = anonymous("/api/resources/actuator/health");

        assertEquals(200, response.statusCode(), "La sonda de salud no exige token");
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

    /**
     * El gateway pone la cabecera antes del proxy y el proxy <em>añade</em> la del servicio, que la
     * devuelve también: salía dos veces, y en el navegador {@code headers.get()} une los dos valores
     * en uno solo, {@code "probe-123, probe-123"}, que deja de servir como referencia.
     */
    @Test
    void theCorrelationIdComesBackOnceAlthoughTheServiceEchoesIt() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/actuator/health"))
                        .header(CORRELATION_HEADER, "probe-123")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(List.of("probe-123"), response.headers().allValues(CORRELATION_HEADER));
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

    /**
     * El 5173 está en la lista del gateway y no en la del servicio. Mientras el gateway reenviaba
     * {@code Origin}, el servicio aplicaba su propio CORS y contestaba 403 a una llamada que el
     * gateway ya había aceptado. Quien decide es el gateway, y lo que deja pasar llega al servicio
     * sin nada que active el CORS de este.
     */
    @Test
    void aCallFromAnOriginTheGatewayAllowsReachesTheServiceWithoutOrigin() throws Exception {
        HttpResponse<String> response = authenticatedFrom("http://localhost:5173", "/api/stock/materials");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("/api/v1/inventory/materials"), RECEIVED_PATHS);
        assertEquals(List.of(), RECEIVED_ORIGINS, "Origin no sale del gateway");
        assertEquals(List.of("http://localhost:5173"),
                response.headers().allValues("Access-Control-Allow-Origin"));
    }

    /**
     * Con un origen que admiten los dos, el gateway ponía su {@code Access-Control-Allow-Origin}
     * antes del proxy y el proxy añadía el del servicio: el navegador rechaza una respuesta que trae
     * dos.
     */
    @Test
    void theBrowserGetsASingleAllowOriginHeader() throws Exception {
        HttpResponse<String> response = authenticatedFrom(SERVICE_ALLOWED_ORIGIN, "/api/stock/materials");

        assertEquals(200, response.statusCode());
        assertEquals(List.of(SERVICE_ALLOWED_ORIGIN), response.headers().allValues("Access-Control-Allow-Origin"));
    }

    /**
     * El {@code Vary} que pone el CORS de Spring en cada respuesta también salía dos veces. Repetido
     * es inocuo, pero un servicio no puede variar por una cabecera que ya no le llega: el que vale es
     * el del gateway.
     */
    @Test
    void theCorsVaryOfTheServiceIsNotRepeated() throws Exception {
        HttpResponse<String> response = authenticatedFrom(SERVICE_ALLOWED_ORIGIN, "/api/stock/materials");

        List<String> vary = varyTokens(response);
        assertTrue(vary.contains("origin"), "El del gateway se queda: " + vary);
        assertEquals(vary.stream().distinct().toList(), vary, "Sin repetidos: " + vary);
    }

    @Test
    void aCallFromAnOriginTheGatewayDoesNotAllowNeverReachesTheService() throws Exception {
        HttpResponse<String> response = authenticatedFrom("http://evil.example", "/api/stock/materials");

        assertEquals(403, response.statusCode());
        assertTrue(RECEIVED_PATHS.isEmpty(), "Lo rechaza el gateway, no el servicio");
    }

    // ------------------------------------------------------------------ de dónde viene la llamada

    /**
     * Los servicios apuntan el primer valor de {@code X-Forwarded-For} como la IP de quien hizo el
     * cambio. Lo que el cliente escriba ahí, o en cualquier otra cabecera de proxy, no puede llegar:
     * llega una sola dirección, la de quien abrió la conexión con el gateway.
     */
    @Test
    void whatTheClientSaysAboutWhereItComesFromNeverReachesTheService() throws Exception {
        HttpResponse<String> response = forged("/api/maintenance/orders", "6.6.6.6");

        assertEquals(200, response.statusCode());
        assertEquals(List.of("x-forwarded-for: 127.0.0.1"), RECEIVED_FORWARDED);
    }

    @Test
    void theServiceLearnsTheAddressTheGatewaySawAlthoughTheClientSaysNothing() throws Exception {
        authenticated("/api/stock/materials");

        assertEquals(List.of("x-forwarded-for: 127.0.0.1"), RECEIVED_FORWARDED);
    }

    /**
     * Como en OpenShift: delante hay un proxy de confianza (el router, aquí el propio test, que
     * llama desde 127.0.0.1) y {@code forward-headers-strategy=framework}, con la que Spring esconde
     * las {@code X-Forwarded-*} y contesta en {@code getRemoteAddr()} el primer valor de la lista,
     * que es el que escribe el cliente.
     */
    @Nested
    @TestPropertySource(properties = {
            "app.forwarded.trusted-proxies=127.0.0.1",
            "server.forward-headers-strategy=framework"
    })
    class BehindATrustedProxy {

        /** El router añade la dirección real a la derecha de lo que el cliente traía escrito. */
        @Test
        void theClientIsTheHopTheTrustedProxyAddedNotTheOneWrittenBeforeIt() throws Exception {
            HttpResponse<String> response = forged("/api/maintenance/orders", "6.6.6.6, 203.0.113.7");

            assertEquals(200, response.statusCode());
            assertEquals(List.of("x-forwarded-for: 203.0.113.7"), RECEIVED_FORWARDED);
        }

        @Test
        void aCallTheProxyMakesOnItsOwnIsTheProxys() throws Exception {
            authenticated("/api/stock/materials");

            assertEquals(List.of("x-forwarded-for: 127.0.0.1"), RECEIVED_FORWARDED);
        }
    }

    // ------------------------------------------------------------------ subidas multipart

    /**
     * La importación de perfiles de mto-configuration sube un .xlsx como {@code multipart/form-data}
     * ({@code POST /profiles/jobs/import?dryRun=}, parte {@code file}); el maestro real pesa 3,3 MB.
     * El gateway no interpreta el multipart: el cuerpo llega al servicio byte a byte, con la misma
     * cabecera {@code Content-Type} —el delimitador va en ella— y el parámetro de la query.
     */
    @Test
    void aFourMegabyteWorkbookReachesTheServiceByteForByte() throws Exception {
        MultipartUpload upload = MultipartUpload.withWorkbookOf(4 * MEGABYTE);

        HttpResponse<String> response = upload(upload, HttpRequest.BodyPublishers.ofByteArray(upload.body()));

        assertTheServiceReceived(upload, response);
    }

    /** 20 MB es lo más que dejan subir las dos aplicaciones web: el fichero entero tiene que caber. */
    @Test
    void aTwentyMegabyteWorkbookReachesTheServiceByteForByte() throws Exception {
        MultipartUpload upload = MultipartUpload.withWorkbookOf(20 * MEGABYTE);

        HttpResponse<String> response = upload(upload, HttpRequest.BodyPublishers.ofByteArray(upload.body()));

        assertTheServiceReceived(upload, response);
    }

    /**
     * Como sube mto-backoffice: su {@code RestClient} escribe el multipart sin saber cuánto va a
     * medir, así que no manda {@code Content-Length} sino {@code Transfer-Encoding: chunked}.
     */
    @Test
    void aWorkbookSentWithoutContentLengthReachesTheServiceByteForByte() throws Exception {
        MultipartUpload upload = MultipartUpload.withWorkbookOf(4 * MEGABYTE);

        HttpResponse<String> response = upload(upload,
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(upload.body())));

        assertTheServiceReceived(upload, response);
    }

    /**
     * Por encima del tope, el gateway contesta él, con su cuerpo de error y el identificador de
     * correlación, y el servicio no ve nada: ni un 500, ni una conexión cortada.
     */
    @Test
    void aBodyOverTheLimitIsAnsweredWithACleanPayloadTooLarge() throws Exception {
        MultipartUpload upload = MultipartUpload.withWorkbookOf(21 * MEGABYTE);

        HttpResponse<String> response = upload(upload, HttpRequest.BodyPublishers.ofByteArray(upload.body()));

        assertPayloadTooLarge(response);
        assertTrue(RECEIVED_PATHS.isEmpty(), "Se rechaza por su Content-Length, antes de llamar al servicio");
    }

    @Test
    void aBodyOverTheLimitFromSomeoneWithoutATokenIsStillUnauthorized() throws Exception {
        MultipartUpload upload = MultipartUpload.withWorkbookOf(21 * MEGABYTE);

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(MultipartUpload.IMPORT_PATH))
                        .header("Content-Type", upload.contentType())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(upload.body()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(), "Quién es va antes que cuánto manda");
    }

    /**
     * El cuerpo de cualquier otra escritura cruza igual: los mismos bytes y el {@code Content-Type}
     * que escribió el cliente, sin un {@code charset} que él no dijo.
     */
    @Test
    void aMergePatchBodyReachesTheServiceWithTheContentTypeTheClientWrote() throws Exception {
        byte[] patch = "{\"description\":\"Tramo de la vía 2\",\"version\":3}".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/maintenance/assets/7"))
                        .header("Authorization", "Bearer stub-token")
                        .header("Content-Type", "application/merge-patch+json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(patch))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(1, RECEIVED_BODIES.size());
        assertEquals("application/merge-patch+json", RECEIVED_BODIES.get(0).contentType());
        assertEquals(new String(patch, StandardCharsets.UTF_8),
                new String(RECEIVED_BODIES.get(0).body(), StandardCharsets.UTF_8));
    }

    /** Un formulario lo lee Tomcat como parámetros y lo recompone el gateway: sigue llegando. */
    @Test
    void aUrlEncodedFormStillReachesTheService() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(gatewayUri("/api/stock/materials"))
                        .header("Authorization", "Bearer stub-token")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("code=CU-107&name=Hilo+de+contacto"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(1, RECEIVED_BODIES.size());
        String form = new String(RECEIVED_BODIES.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(form.contains("code=CU-107") && form.contains("name=Hilo+de+contacto"), form);
    }

    @Nested
    @TestPropertySource(properties = "app.request.max-body-size=64KB")
    class WithASmallBodyLimit {

        /**
         * Sin {@code Content-Length} no se sabe cuánto mide hasta haberlo leído. El gateway lo
         * recibe entero antes de llamar, así que el tope salta sin que el servicio vea nada.
         */
        @Test
        void aBodyWithoutContentLengthOverTheLimitNeverReachesTheService() throws Exception {
            MultipartUpload upload = MultipartUpload.withWorkbookOf(MEGABYTE);

            HttpResponse<String> response = upload(upload,
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(upload.body())));

            assertPayloadTooLarge(response);
            assertTrue(RECEIVED_PATHS.isEmpty(), "No se ha llamado al servicio");
        }

        /**
         * Un cuerpo demasiado grande es un error de quien lo manda, no del servicio: si contara como
         * fallo, una docena de subidas así abrirían el circuito y dejarían a todos sin el servicio.
         */
        @Test
        void bodiesOverTheLimitDoNotOpenTheCircuitOfTheService() throws Exception {
            MultipartUpload upload = MultipartUpload.withWorkbookOf(MEGABYTE);
            for (int i = 0; i < 25; i++) {
                assertPayloadTooLarge(upload(upload,
                        HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(upload.body()))));
            }

            assertEquals(200, authenticated("/api/configuration/profiles").statusCode());
        }

        /** También en una ruta sin circuito, la de Actuator del servicio. */
        @Test
        void aBodyOverTheLimitOnARouteWithoutCircuitIsAlsoPayloadTooLarge() throws Exception {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(gatewayUri("/api/configuration/actuator/loggers/x"))
                            .header("Authorization", "Bearer stub-token")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(
                                    () -> new ByteArrayInputStream(new byte[MEGABYTE])))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertPayloadTooLarge(response);
            assertTrue(RECEIVED_PATHS.isEmpty(), "No se ha llamado al servicio");
        }

        @Test
        void aBodyUnderTheLimitStillPasses() throws Exception {
            MultipartUpload upload = MultipartUpload.withWorkbookOf(16 * 1024);

            HttpResponse<String> response = upload(upload,
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(upload.body())));

            assertTheServiceReceived(upload, response);
        }
    }

    /**
     * Los timeouts hacia el servicio, encogidos para que el test no tarde: 1 s de lectura y 2 s de
     * circuito en vez de 15 s y 20 s.
     */
    @Nested
    @TestPropertySource(properties = {
            "spring.http.clients.read-timeout=1s",
            "resilience4j.timelimiter.configs.default.timeout-duration=2s"
    })
    class WithShortTimeouts {

        /**
         * Una subida que tarda en llegar más que los dos timeouts juntos. Miden al servicio, no la
         * línea del cliente: mientras el proxy copiaba el cuerpo al vuelo, esta subida acababa en el
         * 503 del fallback —o en una conexión cortada— con el servicio sano.
         */
        @Test
        void anUploadThatTakesLongerToSendThanTheTimeoutsStillArrives() throws Exception {
            MultipartUpload upload = MultipartUpload.withWorkbookOf(MEGABYTE);

            HttpResponse<String> response = upload(upload,
                    HttpRequest.BodyPublishers.ofInputStream(() -> new TricklingInputStream(upload.body(), 4000)));

            assertTheServiceReceived(upload, response);
        }
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

    /** Una llamada con todas las cabeceras de proxy escritas por quien llama. */
    private HttpResponse<String> forged(String path, String forwardedFor) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(path))
                        .header("Authorization", "Bearer stub-token")
                        .header("X-Forwarded-For", forwardedFor)
                        .header("Forwarded", "for=6.6.6.6;proto=https;host=evil.example")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443")
                        .header("X-Forwarded-Prefix", "/evil")
                        .header("X-Real-IP", "6.6.6.6")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Una llamada de navegador desde otro origen: la que no es un preflight. */
    private HttpResponse<String> authenticatedFrom(String origin, String path) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(path))
                        .header("Authorization", "Bearer stub-token")
                        .header("Origin", origin)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(MultipartUpload upload, HttpRequest.BodyPublisher publisher) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(gatewayUri(MultipartUpload.IMPORT_PATH))
                        .header("Authorization", "Bearer stub-token")
                        .header("Content-Type", upload.contentType())
                        .POST(publisher)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertTheServiceReceived(MultipartUpload upload, HttpResponse<String> response) {
        assertEquals(202, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"jobId\":42"), "El trabajo que contesta el servicio vuelve tal cual");
        assertEquals(List.of("/api/v1/configuration/profiles/jobs/import?dryRun=true"), RECEIVED_PATHS);

        assertEquals(1, RECEIVED_BODIES.size());
        ReceivedBody received = RECEIVED_BODIES.get(0);
        assertEquals(upload.contentType(), received.contentType(), "El delimitador viaja en esta cabecera");
        assertEquals(upload.body().length, received.body().length, "El cuerpo mide lo mismo");
        assertEquals(sha256(upload.body()), sha256(received.body()), "Y es el mismo, byte a byte");

        // Lo que leería el servicio al interpretar el multipart: las mismas partes, con su nombre,
        // su fichero, su tipo y su contenido.
        assertEquals(
                List.of(
                        new ReceivedPart("file", "profile-master.xlsx", XLSX,
                                upload.workbook().length, sha256(upload.workbook())),
                        new ReceivedPart("note", null, "text/plain; charset=UTF-8",
                                MultipartUpload.NOTE.getBytes(StandardCharsets.UTF_8).length,
                                sha256(MultipartUpload.NOTE.getBytes(StandardCharsets.UTF_8)))),
                received.parts());
    }

    private static void assertPayloadTooLarge(HttpResponse<String> response) {
        assertEquals(413, response.statusCode(), response.body());
        assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElse(null));
        String correlationId = response.headers().firstValue(CORRELATION_HEADER).orElseThrow();
        assertTrue(response.body().contains("\"correlationId\":\"" + correlationId + "\""), response.body());
        assertTrue(response.body().contains("\"status\":413"), response.body());
        assertTrue(response.body().contains("\"maxBodySize\":"), "Dice cuál es el tope: " + response.body());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Una subida como la de las aplicaciones web, generada aquí y no leída de un fichero: una parte
     * {@code file} binaria del tamaño pedido y una de texto. Los bytes salen de una semilla fija, así
     * que un fallo se repite igual.
     */
    private record MultipartUpload(String boundary, byte[] workbook, byte[] body) {

        static final String IMPORT_PATH = "/api/configuration/profiles/jobs/import?dryRun=true";
        static final String NOTE = "importación de prueba";

        static MultipartUpload withWorkbookOf(int size) {
            byte[] workbook = new byte[size];
            new Random(size).nextBytes(workbook);
            String boundary = "----mto-gateway-test-" + size;

            ByteArrayOutputStream body = new ByteArrayOutputStream(size + 1024);
            body.writeBytes(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"profile-master.xlsx\"\r\n"
                    + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(workbook);
            body.writeBytes(("\r\n--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"note\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    + NOTE
                    + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return new MultipartUpload(boundary, workbook, body.toByteArray());
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }
    }

    /** Un cuerpo que sale a trozos repartidos en el tiempo, como por una línea lenta. */
    private static final class TricklingInputStream extends ByteArrayInputStream {

        private static final int CHUNKS = 20;
        private final int chunkSize;
        private final long pauseMillis;

        private TricklingInputStream(byte[] content, long totalMillis) {
            super(content);
            this.chunkSize = Math.max(1, content.length / CHUNKS);
            this.pauseMillis = totalMillis / CHUNKS;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            try {
                Thread.sleep(pauseMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.read(buffer, offset, Math.min(length, chunkSize));
        }
    }

    /** Una parte tal como la vería el servicio. */
    private record ReceivedPart(String name, String filename, String contentType, int size, String sha256) {
    }

    private record ReceivedBody(String contentType, byte[] body) {

        /**
         * El multipart interpretado a mano, a partir del delimitador de la cabecera que llegó: si el
         * gateway la cambiara o reescribiera el cuerpo, las partes no saldrían.
         */
        List<ReceivedPart> parts() {
            String marker = "boundary=";
            byte[] delimiter = ("--" + contentType.substring(contentType.indexOf(marker) + marker.length()))
                    .getBytes(StandardCharsets.UTF_8);
            byte[] headersEnd = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

            List<ReceivedPart> parts = new ArrayList<>();
            int start = indexOf(body, delimiter, 0);
            while (start >= 0) {
                int next = indexOf(body, delimiter, start + delimiter.length);
                if (next < 0) {
                    break;
                }
                int headersStart = start + delimiter.length + 2;
                int contentStart = indexOf(body, headersEnd, headersStart) + headersEnd.length;
                String headers = new String(body, headersStart, contentStart - headersStart, StandardCharsets.UTF_8);
                // El CRLF de antes del delimitador es del delimitador, no del contenido.
                byte[] content = Arrays.copyOfRange(body, contentStart, next - 2);
                parts.add(new ReceivedPart(
                        between(headers, "name=\"", "\""),
                        between(headers, "filename=\"", "\""),
                        between(headers, "Content-Type: ", "\r\n"),
                        content.length,
                        sha256(content)));
                start = next;
            }
            return parts;
        }

        private static String between(String text, String from, String to) {
            int start = text.indexOf(from);
            if (start < 0) {
                return null;
            }
            start += from.length();
            return text.substring(start, text.indexOf(to, start));
        }

        private static int indexOf(byte[] haystack, byte[] needle, int from) {
            outer:
            for (int i = from; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    /** Los valores de {@code Vary}, vengan en líneas separadas o unidos por comas, en minúsculas. */
    private static List<String> varyTokens(HttpResponse<?> response) {
        return response.headers().allValues("Vary").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .filter(token -> !token.isEmpty())
                .toList();
    }
}
