package com.alejandro.mtogateway.filter;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lo que el servicio sabe de quién llama lo dice el gateway, no el cliente: de la petición se quitan
 * {@code Forwarded}, todas las {@code X-Forwarded-*} y {@code X-Real-IP}, y se pone un único
 * {@code X-Forwarded-For} con una sola dirección, la del cliente tal como la vio el gateway.
 *
 * <p>Los servicios apuntan el primer valor de {@code X-Forwarded-For} como la IP de quien hizo el
 * cambio (la revisión de Envers de mto-maintenance, mto-stock, mto-configuration y mto-resources).
 * Una cabecera que el cliente pudiera escribir sería una IP de auditoría que el cliente elige. Por
 * eso el valor es uno solo: no hay «primer salto» que alguien pueda poner delante.</p>
 *
 * <p>Quién es el cliente:</p>
 * <ul>
 *   <li>Sin {@code app.forwarded.trusted-proxies}, quien abrió la conexión con el gateway. Lo que
 *       traiga en sus cabeceras no cuenta.</li>
 *   <li>Con ella, se recorre el {@code X-Forwarded-For} que llegó de derecha a izquierda, empezando
 *       por quien abrió la conexión: mientras la dirección que se tiene sea de un proxy de confianza,
 *       se le cree el salto que él añadió. La primera que no es de confianza es el cliente, y lo que
 *       haya a su izquierda lo escribió él y se ignora. Un valor que no es una dirección IP corta el
 *       recorrido.</li>
 * </ul>
 *
 * <p>La conexión y sus cabeceras se leen de la petición de Tomcat, por debajo de cualquier envoltorio:
 * con {@code server.forward-headers-strategy=framework} el {@code ForwardedHeaderFilter} de Spring
 * esconde las {@code X-Forwarded-*} y contesta en {@code getRemoteAddr()} el primer valor de
 * {@code X-Forwarded-For}, que es justo el que puede escribir cualquiera. El resultado es el mismo
 * con esa estrategia y sin ella.</p>
 *
 * <p>Va el último ({@link Ordered#LOWEST_PRECEDENCE}) para que no dependa de los filtros de serie de
 * Spring Cloud Gateway: sin su {@code trusted-proxies} quitan las {@code X-Forwarded-*} (y quitarían
 * la de aquí si corrieran después), y con ella añaden las suyas, que este vuelve a quitar. Es un bean
 * y no un filtro de ruta por lo mismo que {@link RemoveCorsRequestHeadersFilter}.</p>
 */
@Component
@EnableConfigurationProperties(ForwardedProperties.class)
public class ClientAddressRequestHeadersFilter implements HttpHeadersFilter.RequestHttpHeadersFilter, Ordered {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final String FORWARDED = "Forwarded";
    private static final String X_FORWARDED_PREFIX = "X-Forwarded-";
    private static final String X_REAL_IP = "X-Real-IP";

    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressRequestHeadersFilter(ForwardedProperties properties) {
        this.trustedProxies = properties.matchers();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        // Se copia en vez de borrar, como hacen los filtros de serie: lo que llega puede ser de
        // solo lectura.
        HttpHeaders filtered = new HttpHeaders();
        for (Map.Entry<String, List<String>> header : input.headerSet()) {
            if (!isForwardingHeader(header.getKey())) {
                filtered.addAll(header.getKey(), header.getValue());
            }
        }
        String clientAddress = clientAddress(connectionRequest(request.servletRequest()));
        if (clientAddress != null) {
            filtered.set(X_FORWARDED_FOR, clientAddress);
        }
        return filtered;
    }

    /**
     * Las cabeceras con las que un proxy dice de dónde viene una llamada. Sin distinguir mayúsculas,
     * como cualquier nombre de cabecera.
     */
    static boolean isForwardingHeader(String name) {
        return FORWARDED.equalsIgnoreCase(name)
                || X_REAL_IP.equalsIgnoreCase(name)
                || name.regionMatches(true, 0, X_FORWARDED_PREFIX, 0, X_FORWARDED_PREFIX.length());
    }

    /** La dirección del cliente, o {@code null} si ni la de la conexión se puede leer como una IP. */
    String clientAddress(HttpServletRequest connection) {
        String client = normalized(connection.getRemoteAddr());
        if (client == null || trustedProxies.isEmpty()) {
            return client;
        }
        List<String> hops = forwardedFor(connection);
        for (int i = hops.size() - 1; i >= 0 && isTrustedProxy(client); i--) {
            String hop = normalized(hops.get(i));
            if (hop == null) {
                break;
            }
            client = hop;
        }
        return client;
    }

    private boolean isTrustedProxy(String address) {
        return trustedProxies.stream().anyMatch(range -> range.matches(address));
    }

    /** Los saltos de {@code X-Forwarded-For} en su orden, vengan en una línea o en varias. */
    private static List<String> forwardedFor(HttpServletRequest connection) {
        List<String> hops = new ArrayList<>();
        for (String line : Collections.list(connection.getHeaders(X_FORWARDED_FOR))) {
            for (String hop : line.split(",")) {
                hops.add(hop.trim());
            }
        }
        return hops;
    }

    /**
     * La dirección en su forma canónica, o {@code null} si no es un literal IP ({@code unknown}, un
     * nombre, una dirección con puerto). {@code ofLiteral} no consulta el DNS.
     */
    private static String normalized(String address) {
        if (address == null) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(address).getHostAddress();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** La petición del contenedor, sin los envoltorios que los filtros le han ido poniendo. */
    private static HttpServletRequest connectionRequest(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequest connection ? connection : request;
    }
}
