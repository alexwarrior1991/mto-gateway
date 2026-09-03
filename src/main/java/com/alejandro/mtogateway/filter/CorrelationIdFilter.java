package com.alejandro.mtogateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un único identificador por petición, presente en la cabecera que sale hacia el servicio de
 * destino, en la respuesta que ve el cliente y en cada línea de log del gateway.
 *
 * <p>Es un filtro de servlet y no un filtro de ruta a propósito. El gateway en su sabor servlet no
 * tiene {@code default-filters}: como filtro de ruta habría que repetirlo en cada bloque del YAML
 * —y olvidarlo al añadir el tercer servicio sería cuestión de tiempo—, además de que un filtro de
 * ruta no puede <em>generar</em> un valor. Tampoco cubriría lo que nunca llega a enrutarse: el
 * Actuator del propio gateway, los <i>preflight</i>, los 404 sin ruta y los 401 de la cadena de
 * seguridad, que son justo las peticiones sobre las que se pregunta cuando algo va mal.</p>
 *
 * <p>Va el primero de todos ({@link Ordered#HIGHEST_PRECEDENCE}) para que el MDC esté puesto antes
 * de que registre nada Spring Security ni el propio despachador.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(CorrelationIdProperties.class)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Clave en el MDC. Se referencia como {@code %X{correlationId}} en el patrón de log. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Lo que llega de fuera se acota antes de reutilizarlo: este valor acaba en una cabecera
     * saliente y en cada línea de log de tres servicios, de modo que un CR/LF permitiría partir la
     * línea e inventarse entradas de log, o colar una cabecera entera contra el servicio de destino.
     *
     * <p>Un valor inválido no se sanea ni se rechaza con un 400: se sustituye por uno nuevo. Un 400
     * convertiría una cabecera de traza —opcional y meramente informativa— en un motivo para tirar
     * la petición.</p>
     */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final CorrelationIdProperties properties;

    public CorrelationIdFilter(CorrelationIdProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(properties.headerName()));

        // Antes de la cadena: en cuanto el proxy empieza a volcar la respuesta del servicio de
        // destino, la respuesta está comprometida y ya no admite cabeceras nuevas.
        response.setHeader(properties.headerName(), correlationId);
        MDC.put(MDC_KEY, correlationId);

        try {
            // El gateway construye su ServerRequest —y con él la petición saliente— a partir del
            // HttpServletRequest que recibe. Envolviéndolo aquí, el identificador viaja aguas abajo
            // también cuando se acaba de generar, y sin que ninguna ruta tenga que declarar nada.
            filterChain.doFilter(new CorrelationIdRequestWrapper(request, correlationId), response);
        } finally {
            // remove y no clear(): el MDC no es nuestro, solo esta clave lo es. Y va en finally
            // porque los hilos se reutilizan: sin limpiar, la siguiente petición que entrara por
            // este hilo heredaría el identificador de la anterior.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(String inboundValue) {
        if (inboundValue != null
                && inboundValue.length() <= properties.maxLength()
                && VALID_ID.matcher(inboundValue).matches()) {
            return inboundValue;
        }

        return UUID.randomUUID().toString();
    }

    /**
     * Sustituye la cabecera en las tres vías por las que se puede leer. Sobrescribir solo
     * {@code getHeader} dejaría el valor viejo visible desde {@code getHeaders}, que es por donde la
     * lee quien copia todas las cabeceras de golpe — que es exactamente lo que hace el proxy.
     */
    private final class CorrelationIdRequestWrapper extends HttpServletRequestWrapper {

        private final String correlationId;

        private CorrelationIdRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        private boolean isCorrelationHeader(String name) {
            return properties.headerName().equalsIgnoreCase(name);
        }

        @Override
        public String getHeader(String name) {
            return isCorrelationHeader(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isCorrelationHeader(name)
                    ? Collections.enumeration(List.of(correlationId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            // Se quita y se vuelve a poner para que no aparezca dos veces cuando la petición ya
            // traía la cabecera escrita con otra combinación de mayúsculas.
            names.removeIf(this::isCorrelationHeader);
            names.add(properties.headerName());
            return Collections.enumeration(names);
        }
    }
}
