package com.alejandro.mtogateway;

import com.alejandro.mtogateway.filter.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
// Boot apaga la exportacion de metricas en los tests salvo que se pida: sin esto, el endpoint de
// Prometheus no se registra en el contexto y la comprobacion de abajo fallaria por una razon que no
// tiene nada que ver con la configuracion del gateway.
@AutoConfigureMetrics
class MtoGatewayApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GatewayMvcProperties gatewayProperties;

    @Test
    void contextLoads() {
        assertNotNull(context.getBean(CorrelationIdFilter.class));
    }

    /**
     * La razón por la que este repositorio dejó de arrastrar {@code spring-boot-starter-data-jpa} y
     * {@code postgresql}: con ellos en el classpath, arrancar el gateway exigía un Postgres delante
     * para no persistir absolutamente nada. Si alguien vuelve a añadirlos por costumbre, este test
     * lo dice antes de que el gateway deje de arrancar en un entorno donde no hay base de datos.
     */
    @Test
    void theGatewayStartsWithoutADataSource() {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length);
    }

    /**
     * Comprobación barata que caza erratas en el YAML: los identificadores de las cuatro rutas y,
     * sobre todo, que las de Actuator tienen un {@code order} menor que las de la API. Que el orden
     * además funcione de verdad lo prueba {@code GatewayRoutingIntegrationTest} contra un servicio
     * real.
     */
    @Test
    void everyRouteIsBoundAndTheActuatorRoutesComeFirst() {
        List<RouteProperties> routes = gatewayProperties.getRoutes();

        assertEquals(
                List.of("mto-configuration-actuator", "mto-stock-actuator",
                        "mto-configuration-api", "mto-stock-api"),
                routes.stream().map(RouteProperties::getId).toList());

        int lastActuatorOrder = Math.max(routes.get(0).getOrder(), routes.get(1).getOrder());
        int firstApiOrder = Math.min(routes.get(2).getOrder(), routes.get(3).getOrder());
        assertTrue(lastActuatorOrder < firstApiOrder,
                "Las rutas de Actuator tienen que evaluarse antes que el comodín de la API");
    }

    /**
     * Las métricas se publican de verdad. El endpoint de Prometheus no lo trae Actuator por sí
     * solo: depende de que {@code micrometer-registry-prometheus} esté en el classpath, y esa
     * dependencia es de ámbito {@code runtime}, así que si alguien la quita nada deja de compilar y
     * el gateway simplemente desaparece del <i>scrape</i> sin avisar.
     */
    @Test
    void thePrometheusEndpointIsAvailable() {
        assertEquals(1, context.getBeanNamesForType(PrometheusScrapeEndpoint.class).length);
    }

    /**
     * Con {@code spring-boot-starter-security} en el classpath, quedarse sin
     * {@code SecurityFilterChain} propio devuelve el control a la cadena por defecto de Boot:
     * formulario de login, CSRF y sesiones delante de una API.
     */
    @Test
    void exactlyOneSecurityFilterChainIsPublished() {
        assertEquals(1, context.getBeanNamesForType(SecurityFilterChain.class).length);
    }
}
