package com.alejandro.mtogateway;

import com.alejandro.mtogateway.filter.RemoveCorsRequestHeadersFilter;
import com.alejandro.mtogateway.filter.RemoveCorsResponseHeadersFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Los dos filtros de cabeceras CORS del proxy, sin contexto de Spring: qué quitan y, sobre todo, qué
 * no. Que el proxy los aplique de verdad a cada ruta lo prueba {@link GatewayRoutingIntegrationTest}.
 */
class CorsHeadersFiltersTest {

    private final RemoveCorsRequestHeadersFilter requestFilter = new RemoveCorsRequestHeadersFilter();
    private final RemoveCorsResponseHeadersFilter responseFilter = new RemoveCorsResponseHeadersFilter();

    // ------------------------------------------------------------------ petición

    @Test
    void theRequestLosesOriginAndTheAccessControlHeadersWhateverTheirCase() {
        HttpHeaders input = new HttpHeaders();
        input.add("origin", "http://localhost:5173");
        input.add("ACCESS-CONTROL-REQUEST-METHOD", "PUT");
        input.add("Access-Control-Request-Headers", "authorization");

        HttpHeaders filtered = requestFilter.apply(input, request());

        assertTrue(filtered.isEmpty(), "Quedan: " + filtered);
    }

    /**
     * Lo que importa es lo que no se toca: sin {@code Authorization} el servicio no autoriza, y sin
     * la correlación se pierde la referencia de la llamada.
     */
    @Test
    void theRequestKeepsEverythingElseWithAllItsValues() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.ORIGIN, "http://localhost:4200");
        input.add(HttpHeaders.AUTHORIZATION, "Bearer token");
        input.add("X-Correlation-Id", "probe-123");
        input.add(HttpHeaders.ACCEPT, "application/json");
        input.add(HttpHeaders.ACCEPT, "application/problem+json");

        HttpHeaders filtered = requestFilter.apply(input, request());

        assertEquals(Set.of(HttpHeaders.AUTHORIZATION, "X-Correlation-Id", HttpHeaders.ACCEPT), filtered.headerNames());
        assertEquals(List.of("Bearer token"), filtered.get(HttpHeaders.AUTHORIZATION));
        assertEquals(List.of("application/json", "application/problem+json"), filtered.get(HttpHeaders.ACCEPT));
    }

    /** Lo que recibe el filtro puede ser de solo lectura: se copia, no se borra. */
    @Test
    void theIncomingHeadersAreLeftAsTheyWere() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.ORIGIN, "http://localhost:4200");

        requestFilter.apply(input, request());

        assertEquals(List.of("http://localhost:4200"), input.get(HttpHeaders.ORIGIN));
    }

    // ------------------------------------------------------------------ respuesta

    @Test
    void theResponseLosesEveryAccessControlHeaderAndKeepsTheRest() {
        HttpHeaders input = new HttpHeaders();
        input.add("Access-Control-Allow-Origin", "*");
        input.add("access-control-allow-credentials", "true");
        input.add("Access-Control-Expose-Headers", "X-Internal");
        input.add("Access-Control-Max-Age", "86400");
        input.add(HttpHeaders.CONTENT_TYPE, "application/json");
        input.add(HttpHeaders.RETRY_AFTER, "30");

        HttpHeaders filtered = responseFilter.apply(input, response());

        assertEquals(Set.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.RETRY_AFTER), filtered.headerNames());
    }

    @Test
    void theCorsTokensOfVaryGoWhetherTheyComeSeparateOrJoinedByCommas() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.VARY, "Origin");
        input.add(HttpHeaders.VARY, "Access-Control-Request-Method, Accept-Encoding");
        input.add(HttpHeaders.VARY, "access-control-request-headers");

        HttpHeaders filtered = responseFilter.apply(input, response());

        assertEquals(List.of("Accept-Encoding"), filtered.get(HttpHeaders.VARY));
    }

    @Test
    void aVaryThatOnlyNamedCorsHeadersDisappears() {
        HttpHeaders input = new HttpHeaders();
        input.add(HttpHeaders.VARY, "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        input.add(HttpHeaders.CONTENT_TYPE, "application/json");

        HttpHeaders filtered = responseFilter.apply(input, response());

        assertFalse(filtered.containsHeader(HttpHeaders.VARY));
        assertEquals(List.of("application/json"), filtered.get(HttpHeaders.CONTENT_TYPE));
    }

    // ------------------------------------------------------------------ utilidades

    /** Los filtros no los leen, pero se les pasan de verdad y no {@code null}: así los llama el proxy. */
    private static ServerRequest request() {
        return ServerRequest.create(new MockHttpServletRequest("GET", "/api/stock/materials"), List.of());
    }

    private static ServerResponse response() {
        return ServerResponse.ok().build();
    }
}
