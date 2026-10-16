package com.alejandro.mtogateway.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.thread.Threading;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El hilo en el que el circuito de Resilience4j hace cada llamada proxificada.
 *
 * <p>Con el {@code TimeLimiter} activo, Spring Cloud CircuitBreaker no llama al servicio en el hilo de
 * la petición: la manda a su propio executor y espera el resultado con el tiempo máximo. Ese executor
 * es por defecto un {@code Executors.newCachedThreadPool()} de hilos de plataforma, y
 * {@code spring.threads.virtual.enabled} no lo toca. Así, con los hilos virtuales activos, cada
 * llamada a la API seguía esperando a su servicio en un hilo del sistema operativo: un servicio lento
 * volvía a costar un hilo por petición en vuelo, que es justo lo que los hilos virtuales evitan.</p>
 *
 * <p>Aquí la llamada va a un hilo virtual nuevo, con nombre para que se reconozca en los logs. El
 * {@code TimeLimiter} no cambia: cancelar interrumpe el hilo virtual igual que a uno de plataforma.
 * Solo con los hilos virtuales activos ({@code SPRING_THREADS_VIRTUAL_ENABLED}); sin ellos queda el
 * pool de siempre. El executor no es un bean a propósito: un {@code Executor} en el contexto haría que
 * Boot dejara de crear su {@code applicationTaskExecutor}.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnThreading(Threading.VIRTUAL)
public class CircuitBreakerExecutorConfiguration {

    static final String THREAD_NAME_PREFIX = "circuit-breaker-";

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> virtualThreadCircuitBreakerExecutor() {
        return factory -> factory.configureExecutorService(virtualThreadExecutor());
    }

    private static ExecutorService virtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(THREAD_NAME_PREFIX, 0).factory());
    }
}
