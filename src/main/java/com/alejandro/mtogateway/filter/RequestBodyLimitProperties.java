package com.alejandro.mtogateway.filter;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Lo más que puede medir el cuerpo de una petición que cruza el gateway.
 *
 * <p>El gateway no interpreta los cuerpos, así que ninguno de los topes de Spring o de Tomcat le
 * pone límite: {@code spring.servlet.multipart.*} no se aplica porque el multipart no se interpreta
 * aquí, y {@code server.tomcat.max-http-form-post-size} solo cuenta los formularios
 * {@code x-www-form-urlencoded}. Sin este, un cuerpo de cualquier tamaño llegaría hasta el servicio.
 * Ver {@link RequestBodyFilter}.</p>
 *
 * <p>21 MB por defecto: los 20 MB de fichero que dejan subir las aplicaciones web a las
 * importaciones de mto-configuration, más el envoltorio del multipart. Un tope que no sea positivo
 * impide arrancar.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.request")
public record RequestBodyLimitProperties(@NotNull DataSize maxBodySize) {

    public RequestBodyLimitProperties {
        if (maxBodySize != null && maxBodySize.toBytes() <= 0) {
            throw new IllegalArgumentException("app.request.max-body-size tiene que ser mayor que cero");
        }
    }
}
