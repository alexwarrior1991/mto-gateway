package com.alejandro.mtogateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.net.InetAddress;
import java.util.List;

/**
 * En quién confía el gateway para saber de dónde viene una llamada.
 *
 * <p>{@code trustedProxies} son las direcciones o rangos CIDR ({@code 10.128.0.0/14},
 * {@code 192.168.1.10}, {@code fd00::/8}) de los proxies que hay delante del gateway y que dicen la
 * verdad en {@code X-Forwarded-For}: el router de OpenShift y, tras él, el nginx de mto-frontend.
 * Vacía por defecto: no se confía en nadie y el cliente es quien abrió la conexión. Ver
 * {@link ClientAddressRequestHeadersFilter}.</p>
 *
 * <p>Un rango mal escrito impide arrancar: mejor eso que un gateway que, sin avisar, no confía en el
 * router y apunta su dirección como la de todo el mundo. Nunca un nombre de host: se compara con la
 * dirección de una conexión y no se consulta el DNS.</p>
 */
@ConfigurationProperties(prefix = "app.forwarded")
public record ForwardedProperties(List<String> trustedProxies) {

    public ForwardedProperties {
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream().map(String::trim).filter(range -> !range.isEmpty()).toList();
        trustedProxies.forEach(ForwardedProperties::requireRange);
    }

    /** Un comparador por rango, en el orden en que se declararon. */
    List<IpAddressMatcher> matchers() {
        return trustedProxies.stream().map(IpAddressMatcher::new).toList();
    }

    private static void requireRange(String range) {
        String address = range.contains("/") ? range.substring(0, range.indexOf('/')) : range;
        try {
            // Solo literales: ofLiteral no resuelve nombres.
            InetAddress.ofLiteral(address);
            new IpAddressMatcher(range);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "app.forwarded.trusted-proxies: '" + range + "' no es una dirección IP ni un rango CIDR", e);
        }
    }
}
