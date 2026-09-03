package com.alejandro.mtogateway.controller;

import com.alejandro.mtogateway.filter.CorrelationIdFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
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
     * Lo que hay que saber de cada servicio para contestar por él: su nombre legible —para que el
     * cuerpo del error diga «mto-stock» y no el segmento de la URL— y el identificador de su
     * circuito, que es de donde se saca el {@code Retry-After}.
     *
     * <p>Es un único mapa a propósito: añadir un servicio ferroviario nuevo toca un solo sitio.
     * Uno que no esté aquí tampoco rompe nada — se usa el segmento de la URL como nombre y la
     * configuración por defecto del registro para la espera.</p>
     */
    private record DownstreamService(String displayName, String circuitBreakerId) {
    }

    private static final Map<String, DownstreamService> SERVICES = Map.of(
            "configuration", new DownstreamService("mto-configuration", "mtoConfiguration"),
            "stock", new DownstreamService("mto-stock", "mtoStock")
    );

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public FallbackController(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ProblemDetail> fallback(@PathVariable String service, HttpServletRequest request) {
        DownstreamService downstream = SERVICES.get(service);
        String serviceName = (downstream != null) ? downstream.displayName() : service;

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
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(downstream)))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }

    /**
     * Cuánto tiene que esperar el cliente, leído de la configuración real del circuito y no escrito
     * a mano: es el tiempo que va a tardar en dejar pasar la primera llamada de prueba, así que
     * reintentar antes solo suma peticiones rechazadas.
     *
     * <p>Antes era la constante {@code "30"}, puesta para casar con
     * {@code wait-duration-in-open-state: 30s}. Cambiar el YAML dejaba la cabecera mintiendo sin
     * que nada lo notara.</p>
     *
     * <p>Si el circuito todavía no está en el registro —no debería pasar, porque al llegar aquí ya
     * se ha ejecutado— se usa la configuración por defecto del propio registro. Nunca un literal.</p>
     */
    private long retryAfterSeconds(DownstreamService downstream) {
        CircuitBreakerConfig config = configOf(downstream);

        // getWaitIntervalFunctionInOpenState() es un Function<Integer, Long> y devuelve
        // milisegundos; el argumento es el numero de intento, que aqui siempre es el primero.
        long waitMillis = config.getWaitIntervalFunctionInOpenState().apply(1);

        // Hacia arriba y con minimo 1: un 'Retry-After: 0' invita a un reintento inmediato que el
        // circuito abierto va a rechazar igual.
        return Math.max(1, Duration.ofMillis(waitMillis).plusMillis(999).toSeconds());
    }

    private CircuitBreakerConfig configOf(DownstreamService downstream) {
        if (downstream == null) {
            return circuitBreakerRegistry.getDefaultConfig();
        }

        return circuitBreakerRegistry.find(downstream.circuitBreakerId())
                .map(circuitBreaker -> circuitBreaker.getCircuitBreakerConfig())
                .orElseGet(circuitBreakerRegistry::getDefaultConfig);
    }

    /**
     * Tras un {@code forward}, {@code getRequestURI()} devuelve {@code /fallback/...}. La ruta que
     * el cliente pidió de verdad queda en este atributo del servlet, y es la única que le sirve para
     * saber qué llamada suya ha fallado.
     */
    private String originalPath(HttpServletRequest request) {
        Object forwarded = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        return (forwarded instanceof String uri) ? uri : request.getRequestURI();
    }
}
