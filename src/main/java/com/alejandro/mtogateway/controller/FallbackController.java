package com.alejandro.mtogateway.controller;

import com.alejandro.mtogateway.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * Respuesta cuando el circuito de un servicio está abierto o la llamada ha fallado.
 *
 * <p>Sin esto, un servicio caído se traduce en un 500 con la traza del proxy dentro: el cliente no
 * sabe si el problema es suyo, del gateway o del servicio, y el 500 invita a reintentar justo
 * cuando menos conviene. Aquí sale un 503 con {@code Retry-After} y un cuerpo que dice qué servicio
 * concreto no está disponible.</p>
 *
 * <p>Se llega por un {@code forward} interno declarado en el {@code fallbackUri} de cada ruta, así
 * que la ruta pública que el cliente pidió no cambia: sigue siendo la suya en {@code instance}.</p>
 */
@RestController
public class FallbackController {

    /**
     * Nombre legible de cada servicio, para que el cuerpo del error diga «mto-stock» y no el
     * identificador de la ruta. Un servicio nuevo que no esté en el mapa no rompe nada: se usa el
     * segmento de la URL tal cual.
     */
    private static final Map<String, String> SERVICE_NAMES = Map.of(
            "configuration", "mto-configuration",
            "stock", "mto-stock"
    );

    /**
     * Coincide con {@code wait-duration-in-open-state} de Resilience4j: es el tiempo que va a tardar
     * el circuito en dejar pasar la primera llamada de prueba, así que reintentar antes solo suma
     * peticiones rechazadas.
     */
    private static final String RETRY_AFTER_SECONDS = "30";

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service, HttpServletRequest request) {
        String serviceName = SERVICE_NAMES.getOrDefault(service, service);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio " + serviceName + " no está disponible en este momento.");
        problemDetail.setTitle("Service Unavailable");
        problemDetail.setInstance(URI.create(originalPath(request)));
        problemDetail.setProperty("service", serviceName);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CorrelationIdFilter.MDC_KEY, correlationId);
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    /**
     * Tras un {@code forward}, {@code getRequestURI()} devuelve {@code /fallback/...}. La ruta que
     * el cliente pidió de verdad queda en este atributo del servlet, y es la única que le sirve para
     * saber qué llamada suya ha fallado.
     */
    private String originalPath(HttpServletRequest request) {
        Object forwarded = request.getAttribute(jakarta.servlet.RequestDispatcher.FORWARD_REQUEST_URI);
        return (forwarded instanceof String uri) ? uri : request.getRequestURI();
    }
}
