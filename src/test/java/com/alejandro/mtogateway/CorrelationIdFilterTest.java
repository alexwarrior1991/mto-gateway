package com.alejandro.mtogateway;

import com.alejandro.mtogateway.filter.CorrelationIdFilter;
import com.alejandro.mtogateway.filter.CorrelationIdProperties;
import com.alejandro.mtogateway.filter.RemoveCorrelationResponseHeaderFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Los filtros de la correlación, sin contexto de Spring: el de servlet, que pone el identificador, y
 * el del proxy, que quita la copia que devuelve el servicio. Son piezas puras y montar el contexto
 * entero solo haría el test más lento y menos concreto.
 */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    private final CorrelationIdFilter filter = new CorrelationIdFilter(new CorrelationIdProperties(HEADER, 64));
    private final RemoveCorrelationResponseHeaderFilter responseFilter =
            new RemoveCorrelationResponseHeaderFilter(new CorrelationIdProperties(HEADER, 64));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesAnIdentifierWhenTheRequestDoesNotBringOne() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(get("/api/stock/materials"), response, capture(seenDownstream));

        assertNotNull(seenDownstream.get());
        assertDoesNotThrow(() -> UUID.fromString(seenDownstream.get()));
        assertEquals(seenDownstream.get(), response.getHeader(HEADER),
                "El identificador que ve el servicio de destino y el que vuelve al cliente son el mismo");
    }

    @Test
    void reusesAValidInboundIdentifier() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader(HEADER, "trace-42_ABC.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, response, capture(seenDownstream));

        assertEquals("trace-42_ABC.9", seenDownstream.get());
        assertEquals("trace-42_ABC.9", response.getHeader(HEADER));
    }

    @Test
    void reusesAnInboundIdentifierWhateverTheCaseOfTheHeaderName() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader("x-correlation-id", "lowercase-header");
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seenDownstream));

        assertEquals("lowercase-header", seenDownstream.get());
    }

    /**
     * El caso que justifica que exista la validación: un valor con CR/LF permitiría partir la línea
     * de log e inventarse entradas, o colar una cabecera entera contra el servicio de destino.
     */
    @Test
    void replacesAnIdentifierThatCouldInjectALogLineOrAHeader() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader(HEADER, "abc\r\nX-Injected: 1");
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seenDownstream));

        assertNotEquals("abc\r\nX-Injected: 1", seenDownstream.get());
        assertDoesNotThrow(() -> UUID.fromString(seenDownstream.get()));
    }

    @Test
    void replacesAnIdentifierWithCharactersOutsideTheAllowedSet() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader(HEADER, "not a valid id!");
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seenDownstream));

        assertDoesNotThrow(() -> UUID.fromString(seenDownstream.get()));
    }

    @Test
    void replacesAnIdentifierLongerThanTheConfiguredLimit() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader(HEADER, "a".repeat(65));
        AtomicReference<String> seenDownstream = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seenDownstream));

        assertEquals(36, seenDownstream.get().length(), "Se ha sustituido por un UUID");
    }

    /**
     * Quien copia todas las cabeceras de golpe —que es lo que hace el proxy— las lee por
     * {@code getHeaders} y {@code getHeaderNames}, no por {@code getHeader}. Si las tres no dijeran
     * lo mismo, el identificador generado no llegaría al servicio de destino.
     */
    @Test
    void theWrapperExposesTheIdentifierThroughEveryHeaderAccessor() throws Exception {
        MockHttpServletRequest request = get("/api/stock/materials");
        request.addHeader("x-correlation-id", "inbound-value");
        AtomicReference<HttpServletRequest> wrapped = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> wrapped.set((HttpServletRequest) req));

        HttpServletRequest downstream = wrapped.get();
        assertEquals("inbound-value", downstream.getHeader(HEADER));
        assertEquals(List.of("inbound-value"), Collections.list(downstream.getHeaders(HEADER)));
        assertEquals(1, Collections.list(downstream.getHeaderNames()).stream()
                        .filter(HEADER::equalsIgnoreCase)
                        .count(),
                "La cabecera aparece una sola vez aunque llegara con otra combinación de mayúsculas");
    }

    @Test
    void putsTheIdentifierInTheMdcWhileTheRequestIsBeingHandled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideTheChain = new AtomicReference<>();

        filter.doFilter(get("/api/stock/materials"), response,
                (req, res) -> mdcInsideTheChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertEquals(response.getHeader(HEADER), mdcInsideTheChain.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "El MDC queda limpio al salir del filtro");
    }

    /**
     * Los hilos se reutilizan: si una petición que falla dejara su identificador en el MDC, la
     * siguiente que entrara por ese hilo lo heredaría y sus logs apuntarían a la petición
     * equivocada.
     */
    @Test
    void clearsTheMdcEvenWhenTheChainFails() {
        FilterChain exploding = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class,
                () -> filter.doFilter(get("/api/stock/materials"), new MockHttpServletResponse(), exploding));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void setsTheResponseHeaderBeforeTheChainRunsSoTheProxyCannotCommitFirst() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerVisibleInsideTheChain = new AtomicReference<>();

        filter.doFilter(get("/api/stock/materials"), response,
                (req, res) -> headerVisibleInsideTheChain.set(
                        ((MockHttpServletResponse) res).getHeader(HEADER)));

        assertNotNull(headerVisibleInsideTheChain.get());
        assertEquals(1, response.getHeaderValues(HEADER).size(), "Una sola vez, no dos");
    }

    /**
     * La copia del servicio sobra: el gateway ya puso la suya antes del proxy, y el proxy la
     * añadiría al lado. Se quita sin distinguir mayúsculas y el resto se queda.
     */
    @Test
    void theServiceCopyOfTheHeaderIsDroppedAndTheRestIsKept() {
        HttpHeaders fromTheService = new HttpHeaders();
        fromTheService.add("x-correlation-id", "probe-123");
        fromTheService.add(HttpHeaders.CONTENT_TYPE, "application/json");
        fromTheService.add(HttpHeaders.RETRY_AFTER, "30");

        HttpHeaders filtered = responseFilter.apply(fromTheService, ServerResponse.ok().build());

        assertEquals(Set.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.RETRY_AFTER), filtered.headerNames());
        assertEquals(List.of("probe-123"), fromTheService.get(HEADER),
                "Lo que llega se copia, no se toca: puede ser de solo lectura");
    }

    /** Sigue al nombre configurado, como el resto de la correlación y la política de CORS. */
    @Test
    void theResponseFilterFollowsTheConfiguredHeaderName() {
        HttpHeaders fromTheService = new HttpHeaders();
        fromTheService.add("X-Trace-Ref", "probe-123");
        fromTheService.add(HEADER, "not-the-configured-one");

        HttpHeaders filtered = new RemoveCorrelationResponseHeaderFilter(new CorrelationIdProperties("X-Trace-Ref", 64))
                .apply(fromTheService, ServerResponse.ok().build());

        assertEquals(Set.of(HEADER), filtered.headerNames());
    }

    private MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private FilterChain capture(AtomicReference<String> target) {
        return (request, response) -> target.set(((HttpServletRequest) request).getHeader(HEADER));
    }
}
