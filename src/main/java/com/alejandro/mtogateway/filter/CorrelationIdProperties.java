package com.alejandro.mtogateway.filter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del identificador de correlación.
 *
 * <p>El nombre de la cabecera es configurable porque no está estandarizado: hay pasarelas que usan
 * {@code X-Request-Id} y organizaciones que arrastran un nombre propio. Lo que no es configurable es
 * que se valide lo que llega de fuera — ver {@link CorrelationIdFilter}.</p>
 *
 * <p>{@code maxLength} acota la longitud de un valor entrante. 64 caracteres cubren de sobra un UUID
 * (36) o un {@code trace-id} de W3C (32); el límite existe para que nadie pueda hacer que el gateway
 * escriba cien kilobytes por línea de log.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.correlation")
public record CorrelationIdProperties(
        @NotBlank String headerName,
        @Min(8) @Max(128) int maxLength
) {
}
