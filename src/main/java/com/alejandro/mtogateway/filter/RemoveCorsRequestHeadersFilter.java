package com.alejandro.mtogateway.filter;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.List;
import java.util.Map;

/**
 * Quita {@code Origin} y las {@code Access-Control-*} de la petición antes de reenviarla: el CORS se
 * resuelve aquí, en el borde, y aguas abajo no tiene que quedar nada que lo active otra vez.
 *
 * <p>Los servicios del dominio ya no tienen CORS propio, pero lo tuvieron, registrado en
 * {@code /**} y con su propia lista de orígenes. Mientras el gateway reenviaba {@code Origin}, una
 * llamada que él había aceptado podía acabar en el 403 del servicio —un origen que el gateway admite
 * y el servicio no—, y cuando lo admitían los dos la respuesta llevaba dos
 * {@code Access-Control-Allow-Origin}, que el navegador rechaza. Sin {@code Origin}, un servicio que
 * traiga su propio CORS (uno nuevo, otra pila) no ve una petición CORS y no hace nada.</p>
 *
 * <p>No se pierde ninguna comprobación: el <i>preflight</i> lo contesta el gateway sin reenviarlo, y
 * una llamada desde un origen que no admite recibe su 403 antes de llegar al proxy. Lo que pasa por
 * aquí ya lo ha autorizado él.</p>
 *
 * <p>Es un bean y no un filtro de ruta: el proxy aplica a todas las rutas cada
 * {@link HttpHeadersFilter.RequestHttpHeadersFilter} del contexto, mientras que en el sabor servlet
 * no hay {@code default-filters} y un filtro de ruta habría que repetirlo en cada bloque del YAML. No
 * sustituye a ninguno de los de serie, que se registran cada uno por su propia clase.</p>
 */
@Component
public class RemoveCorsRequestHeadersFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    private static final String ACCESS_CONTROL_PREFIX = "Access-Control-";

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        // Se copia en vez de borrar, como hacen los filtros de serie: lo que llega puede ser de
        // solo lectura.
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            if (!isCorsHeader(header.getKey())) {
                filtered.addAll(header.getKey(), header.getValue());
            }
        }
        return filtered;
    }

    /**
     * Las cabeceras que solo tienen sentido entre el navegador y quien resuelve el CORS. Sin
     * distinguir mayúsculas, como cualquier nombre de cabecera.
     */
    static boolean isCorsHeader(String name) {
        return HttpHeaders.ORIGIN.equalsIgnoreCase(name)
                || name.regionMatches(true, 0, ACCESS_CONTROL_PREFIX, 0, ACCESS_CONTROL_PREFIX.length());
    }
}
