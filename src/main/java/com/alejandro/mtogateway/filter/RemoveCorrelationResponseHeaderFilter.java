package com.alejandro.mtogateway.filter;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;

/**
 * Quita de la respuesta del servicio la cabecera de correlación: la del gateway ya está puesta.
 *
 * <p>{@link CorrelationIdFilter} la escribe en cada respuesta antes del proxy, y el proxy
 * <em>añade</em> las cabeceras del servicio a las que ya hay. {@code mto-configuration},
 * {@code mto-users} y {@code mto-notification} la devuelven también, así que salía dos veces, y en
 * el navegador {@code headers.get()} une los dos valores en uno ({@code "id, id"}) que ya no sirve
 * como referencia.</p>
 *
 * <p>Quitar la del servicio no pierde nada: los tres aplican las mismas reglas que el gateway (el
 * mismo nombre, como mucho 64 caracteres de {@code [A-Za-z0-9._-]}), y el gateway solo reenvía un
 * identificador que las cumple, así que el servicio lo reutiliza y devuelve el mismo. Si un servicio
 * validara más estricto y generara el suyo, la cabecera seguiría diciendo el del gateway, el mismo
 * que lleva lo que contesta él solo (los 401 y 403, los 404 sin ruta, el 503 del fallback): el del
 * servicio quedaría en sus logs y en el cuerpo de sus errores.</p>
 *
 * <p>Sigue a {@code app.correlation.header-name}, como el resto de la correlación. Es un bean, como
 * los filtros de CORS, porque el proxy aplica a todas las rutas cada
 * {@link HttpHeadersFilter.ResponseHttpHeadersFilter} del contexto.</p>
 */
@Component
public class RemoveCorrelationResponseHeaderFilter implements HttpHeadersFilter.ResponseHttpHeadersFilter {

    private final CorrelationIdProperties properties;

    public RemoveCorrelationResponseHeaderFilter(CorrelationIdProperties properties) {
        this.properties = properties;
    }

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerResponse response) {
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            if (!properties.headerName().equalsIgnoreCase(header.getKey())) {
                filtered.addAll(header.getKey(), header.getValue());
            }
        }
        return filtered;
    }
}
