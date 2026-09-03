package com.alejandro.mtogateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La política de CORS sigue al nombre configurado de la cabecera de correlación.
 *
 * <p>El nombre se cambia a propósito a uno que no es el de serie. Con {@code X-Correlation-Id} este
 * test pasaría igual estando el acoplamiento roto —que es como estaba: el nombre iba escrito a mano
 * en las dos listas del YAML, y cambiar {@code app.correlation.header-name} dejaba al navegador sin
 * poder leer la cabecera sin que nada avisara.</p>
 *
 * <p>Va en su propio contexto porque es lo que exige cambiar una property de arranque.</p>
 */
@SpringBootTest(properties = "app.correlation.header-name=X-Trace-Ref")
class GatewayCorsHeaderNameTest {

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    private CorsConfiguration corsConfiguration() {
        CorsConfiguration configuration = corsConfigurationSource.getCorsConfiguration(
                new MockHttpServletRequest("GET", "/api/stock/materials"));
        assertNotNull(configuration, "La política se registra en /**");
        return configuration;
    }

    /**
     * Sin exponerla, el navegador ve llegar la cabecera y no deja leerla desde JavaScript: sería
     * inútil justo para quien tiene que pegarla en un informe de error.
     */
    @Test
    void theConfiguredCorrelationHeaderIsExposedToTheBrowser() {
        assertTrue(corsConfiguration().getExposedHeaders().contains("X-Trace-Ref"),
                "Expuestas: " + corsConfiguration().getExposedHeaders());
    }

    @Test
    void theConfiguredCorrelationHeaderIsAcceptedOnTheWayIn() {
        assertTrue(corsConfiguration().getAllowedHeaders().contains("X-Trace-Ref"),
                "Admitidas: " + corsConfiguration().getAllowedHeaders());
    }

    /** Lo que sí está en el YAML sigue estando: la cabecera se añade, no sustituye a la lista. */
    @Test
    void theHeadersDeclaredInTheYamlAreKept() {
        assertTrue(corsConfiguration().getAllowedHeaders().contains("Authorization"));
        assertTrue(corsConfiguration().getExposedHeaders().contains("Location"));
    }

    /** El nombre de serie ya no aparece por ningún lado: vivía en un solo sitio y se ha movido. */
    @Test
    void theDefaultHeaderNameIsNotHardcodedAnywhere() {
        assertTrue(corsConfiguration().getExposedHeaders().stream()
                        .noneMatch("X-Correlation-Id"::equalsIgnoreCase),
                "Expuestas: " + corsConfiguration().getExposedHeaders());
    }
}
