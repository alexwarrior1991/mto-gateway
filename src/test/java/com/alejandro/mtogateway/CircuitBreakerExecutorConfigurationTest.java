package com.alejandro.mtogateway;

import com.alejandro.mtogateway.configuration.CircuitBreakerExecutorConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El circuito hace la llamada proxificada en un hilo virtual cuando los hilos virtuales están activos,
 * y en el pool de plataforma de siempre cuando no. Que las rutas sigan pasando por el circuito, con su
 * fallback y su traza, lo prueban {@link GatewayFallbackIntegrationTest} y
 * {@link GatewayTracingIntegrationTest}.
 */
class CircuitBreakerExecutorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CircuitBreakerExecutorConfiguration.class);

    @Test
    @SuppressWarnings("unchecked")
    void withVirtualThreadsTheProxiedCallRunsOnANamedVirtualThread() {
        contextRunner.withPropertyValues("spring.threads.virtual.enabled=true").run(context -> {
            Resilience4JCircuitBreakerFactory factory = factory();
            context.getBean(Customizer.class).customize(factory);

            Thread thread = factory.create("mtoStock").run(Thread::currentThread);

            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.getName()).startsWith("circuit-breaker-");
        });
    }

    @Test
    void withoutVirtualThreadsTheFactoryKeepsItsOwnPlatformPool() {
        contextRunner.withPropertyValues("spring.threads.virtual.enabled=false").run(context -> {
            assertThat(context.getBeansOfType(Customizer.class)).isEmpty();

            Thread thread = factory().create("mtoStock").run(Thread::currentThread);

            assertThat(thread.isVirtual()).isFalse();
            assertThat(thread).isNotSameAs(Thread.currentThread());
        });
    }

    private static Resilience4JCircuitBreakerFactory factory() {
        return new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), null);
    }
}
