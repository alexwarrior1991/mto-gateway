package com.alejandro.mtogateway.filter;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Quita de la respuesta del servicio lo que es del CORS: todas las {@code Access-Control-*} y, de
 * {@code Vary}, lo que nombra una cabecera de petición CORS.
 *
 * <p>El {@code CorsFilter} del gateway escribe sus cabeceras antes de que exista la respuesta del
 * servicio, y el proxy <em>añade</em> las del servicio a las que ya hay: un
 * {@code Access-Control-Allow-Origin} del servicio sería el segundo, y el navegador rechaza la
 * respuesta entera. Con {@link RemoveCorsRequestHeadersFilter}, un servicio con el CORS de Spring ya
 * no pone ninguno, porque no le llega {@code Origin}; esto cubre al que lo pusiera igualmente, con
 * otra pila u otra configuración.</p>
 *
 * <p>El {@code Vary: Origin} que Spring pone en cada respuesta sí sigue llegando, y aguas abajo no
 * significa nada: el servicio no puede variar por una cabecera que ya no recibe. El que vale es el
 * del gateway. El resto de {@code Vary} se conserva.</p>
 *
 * <p>Lo que contesta el propio gateway —sus endpoints, el 503 del fallback, al que se llega por un
 * {@code forward}— no pasa por aquí: ahí no hay más CORS que el suyo.</p>
 */
@Component
public class RemoveCorsResponseHeadersFilter implements HttpHeadersFilter.ResponseHttpHeadersFilter {

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerResponse response) {
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            String name = header.getKey();
            if (HttpHeaders.VARY.equalsIgnoreCase(name)) {
                List<String> kept = withoutCorsTokens(header.getValue());
                if (!kept.isEmpty()) {
                    filtered.addAll(name, kept);
                }
            } else if (!RemoveCorsRequestHeadersFilter.isCorsHeader(name)) {
                filtered.addAll(name, header.getValue());
            }
        }
        return filtered;
    }

    /** {@code Vary} puede llegar en varias líneas o en una sola, separada por comas. */
    private static List<String> withoutCorsTokens(List<String> vary) {
        return vary.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty() && !RemoveCorsRequestHeadersFilter.isCorsHeader(token))
                .toList();
    }
}
