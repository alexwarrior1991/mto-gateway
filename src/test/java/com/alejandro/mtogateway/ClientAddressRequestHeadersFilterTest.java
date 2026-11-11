package com.alejandro.mtogateway;

import com.alejandro.mtogateway.filter.ClientAddressRequestHeadersFilter;
import com.alejandro.mtogateway.filter.ForwardedProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * La dirección del cliente que el gateway dice a los servicios, sin contexto de Spring: a quién se
 * cree y a quién no. Que el proxy aplique el filtro de verdad, y el último, lo prueba
 * {@link GatewayRoutingIntegrationTest}.
 */
class ClientAddressRequestHeadersFilterTest {

    private static final String ROUTER = "10.128.2.15";
    private static final String FRONTEND = "10.131.0.44";
    private static final String CLUSTER = "10.128.0.0/14";

    // ------------------------------------------------------------------ sin confiar en nadie

    @Test
    void withoutTrustedProxiesTheClientIsWhoeverOpenedTheConnection() {
        HttpHeaders filtered = trusting().apply(headersOf(from("198.51.100.9", "6.6.6.6")),
                serverRequest(from("198.51.100.9", "6.6.6.6")));

        assertEquals(List.of("198.51.100.9"), filtered.get("X-Forwarded-For"));
    }

    @Test
    void everyHeaderAProxyWouldWriteIsDroppedWhateverItsCase() {
        MockHttpServletRequest request = from("198.51.100.9");
        HttpHeaders input = new HttpHeaders();
        input.add("forwarded", "for=6.6.6.6;proto=https;host=evil.example");
        input.add("x-forwarded-for", "6.6.6.6");
        input.add("X-FORWARDED-HOST", "evil.example");
        input.add("X-Forwarded-Proto", "https");
        input.add("X-Forwarded-Port", "443");
        input.add("X-Forwarded-Prefix", "/evil");
        input.add("X-Forwarded-Ssl", "on");
        input.add("x-real-ip", "6.6.6.6");

        HttpHeaders filtered = trusting().apply(input, serverRequest(request));

        assertEquals(Set.of("X-Forwarded-For"), filtered.headerNames());
        assertEquals(List.of("198.51.100.9"), filtered.get("X-Forwarded-For"));
    }

    /** Sin {@code Authorization} el servicio no autoriza, y sin la correlación se pierde la referencia. */
    @Test
    void theRestOfTheRequestIsKeptAndTheIncomingHeadersAreLeftAsTheyWere() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.AUTHORIZATION, "Bearer token");
        input.add("X-Correlation-Id", "probe-123");
        input.add(HttpHeaders.ACCEPT, "application/json");
        input.add(HttpHeaders.ACCEPT, "application/problem+json");
        input.add("X-Forwarded-For", "6.6.6.6");

        HttpHeaders filtered = trusting().apply(input, serverRequest(from("198.51.100.9")));

        assertEquals(Set.of(HttpHeaders.AUTHORIZATION, "X-Correlation-Id", HttpHeaders.ACCEPT, "X-Forwarded-For"),
                filtered.headerNames());
        assertEquals(List.of("application/json", "application/problem+json"), filtered.get(HttpHeaders.ACCEPT));
        assertEquals(List.of("6.6.6.6"), input.get("X-Forwarded-For"));
    }

    // ------------------------------------------------------------------ con proxies de confianza

    /** El router añade la dirección real a la derecha de lo que el cliente traía escrito. */
    @Test
    void theClientIsTheFirstHopFromTheRightThatIsNotATrustedProxy() {
        assertEquals("203.0.113.7", clientOf(trusting(CLUSTER), from(ROUTER, "6.6.6.6, 203.0.113.7")));
    }

    /** Navegador → router → nginx de mto-frontend → gateway: dos proxies de confianza seguidos. */
    @Test
    void severalTrustedProxiesInARowAreAllWalkedThrough() {
        assertEquals("203.0.113.7",
                clientOf(trusting(CLUSTER), from(FRONTEND, "6.6.6.6", "203.0.113.7, " + ROUTER)));
    }

    /** Un pod que no está en la lista no es un proxy: lo que diga de otros no cuenta. */
    @Test
    void whatAnUntrustedCallerWritesIsIgnoredAlthoughThereAreTrustedProxies() {
        assertEquals("198.51.100.9", clientOf(trusting(CLUSTER), from("198.51.100.9", "6.6.6.6, " + ROUTER)));
    }

    /** mto-backoffice llama servidor a servidor, sin {@code X-Forwarded-For}: el cliente es él. */
    @Test
    void aTrustedProxyThatSaysNothingIsItselfTheClient() {
        assertEquals(FRONTEND, clientOf(trusting(CLUSTER), from(FRONTEND)));
    }

    @Test
    void whenEveryHopIsTrustedTheClientIsTheFarthestOne() {
        assertEquals("10.129.0.1", clientOf(trusting(CLUSTER), from(ROUTER, "10.129.0.1, 10.130.0.2")));
    }

    /** {@code unknown}, un nombre o una dirección con puerto no se reenvían como si fueran una IP. */
    @Test
    void aHopThatIsNotAnIpAddressStopsTheWalk() {
        ClientAddressRequestHeadersFilter filter = trusting(CLUSTER);

        assertEquals(ROUTER, clientOf(filter, from(ROUTER, "203.0.113.7, unknown")));
        assertEquals(ROUTER, clientOf(filter, from(ROUTER, "evil.example")));
        assertEquals(ROUTER, clientOf(filter, from(ROUTER, "203.0.113.7:4711")));
        assertEquals(ROUTER, clientOf(filter, from(ROUTER, "")));
    }

    @Test
    void singleAddressesAndIpv6RangesAreTrustedToo() {
        ClientAddressRequestHeadersFilter filter = trusting("192.168.1.10", "fd00::/8");

        assertEquals("203.0.113.7", clientOf(filter, from("192.168.1.10", "203.0.113.7")));
        assertEquals("2001:db8:0:0:0:0:0:7", clientOf(filter, from("fd00::15", "2001:db8::7")));
        assertEquals("192.168.1.11", clientOf(filter, from("192.168.1.11", "203.0.113.7")));
    }

    /**
     * Con {@code forward-headers-strategy=framework}, Spring esconde las {@code X-Forwarded-*} y
     * contesta en {@code getRemoteAddr()} el primer valor de la lista, el que escribe el cliente. El
     * filtro lee la conexión por debajo de ese envoltorio.
     */
    @Test
    void theConnectionIsReadBelowTheWrapperOfTheForwardedHeaderFilter() throws Exception {
        HttpServletRequest wrapped = wrappedByTheForwardedHeaderFilter(from(ROUTER, "6.6.6.6, 203.0.113.7"));
        assertEquals("6.6.6.6", wrapped.getRemoteAddr(), "El envoltorio ya no miente: el test no prueba nada");

        assertEquals("203.0.113.7", clientOf(trusting(CLUSTER), wrapped));
        assertEquals(ROUTER, clientOf(trusting(), wrapped));
    }

    // ------------------------------------------------------------------ configuración

    @Test
    void aRangeThatIsNotAnAddressStopsTheStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ForwardedProperties(List.of("router.example")));
        assertThrows(IllegalArgumentException.class, () -> new ForwardedProperties(List.of("10.128.0.0/99")));
        assertThrows(IllegalArgumentException.class, () -> new ForwardedProperties(List.of(".*")));
    }

    /** Una variable de entorno vacía o con espacios de más es «nadie», no un error ni «todos». */
    @Test
    void anEmptyListTrustsNobody() {
        assertEquals(List.of(), new ForwardedProperties(null).trustedProxies());
        assertEquals(List.of(), new ForwardedProperties(List.of("", " ")).trustedProxies());
        assertEquals(List.of(CLUSTER), new ForwardedProperties(List.of(" " + CLUSTER + " ")).trustedProxies());
        assertNotEquals("203.0.113.7", clientOf(trusting(""), from(ROUTER, "203.0.113.7")));
    }

    // ------------------------------------------------------------------ utilidades

    private static ClientAddressRequestHeadersFilter trusting(String... ranges) {
        return new ClientAddressRequestHeadersFilter(new ForwardedProperties(Arrays.asList(ranges)));
    }

    /** Lo que el filtro deja en {@code X-Forwarded-For}: un único valor. */
    private static String clientOf(ClientAddressRequestHeadersFilter filter, HttpServletRequest request) {
        HttpHeaders filtered = filter.apply(headersOf(request), serverRequest(request));
        List<String> values = filtered.get("X-Forwarded-For");
        assertEquals(1, values.size(), "Un solo valor: " + values);
        assertFalse(values.getFirst().contains(","), "Una sola dirección: " + values);
        return values.getFirst();
    }

    /** Una conexión desde esa dirección, con esas líneas de {@code X-Forwarded-For}. */
    private static MockHttpServletRequest from(String remoteAddress, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/maintenance/orders/1");
        request.setRemoteAddr(remoteAddress);
        for (String line : forwardedFor) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    private static HttpServletRequest wrappedByTheForwardedHeaderFilter(MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    /** Las cabeceras como las recibe el filtro del proxy: las de la petición que él ve. */
    private static HttpHeaders headersOf(HttpServletRequest request) {
        return ServerRequest.create(request, List.of()).headers().asHttpHeaders();
    }

    private static ServerRequest serverRequest(HttpServletRequest request) {
        return ServerRequest.create(request, List.of());
    }
}
