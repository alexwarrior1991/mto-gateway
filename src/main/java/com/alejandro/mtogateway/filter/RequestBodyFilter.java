package com.alejandro.mtogateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * El cuerpo de una petición se recibe entero antes de llamar al servicio, y con un tope: por encima,
 * un 413 del gateway con su cuerpo de error y el identificador de correlación.
 *
 * <p>Los bytes no se tocan ni se interpretan —un multipart llega al servicio tal como salió del
 * cliente, con su delimitador—; lo único que cambia es cuándo se abre la llamada al servicio:
 * cuando el cuerpo ya está aquí. Copiarlo al vuelo, que es lo que hace el proxy por sí solo, tenía
 * tres consecuencias:</p>
 * <ul>
 *   <li>Los timeouts hacia el servicio ({@code read-timeout} y el del circuito) cuentan desde que se
 *   abre la llamada, así que incluían lo que el cliente tardaba en mandar el cuerpo: una subida por
 *   una línea lenta acababa en el 503 del fallback, o en una conexión cortada, con el servicio
 *   perfectamente sano, y contaba como fallo suyo para abrir el circuito.</li>
 *   <li>Un cliente lento retenía una conexión y un hilo del servicio durante toda la subida.</li>
 *   <li>Sin {@code Content-Length} ({@code Transfer-Encoding: chunked}, como sube mto-backoffice) no
 *   se sabe cuánto mide un cuerpo hasta haberlo leído: el tope habría saltado con la llamada al
 *   servicio ya abierta.</li>
 * </ul>
 *
 * <p>Hasta {@link #MEMORY_THRESHOLD} el cuerpo se guarda en memoria, que es el caso de cualquier
 * JSON; por encima, en un fichero temporal que se borra al terminar la petición. Con
 * {@code Content-Length} por encima del tope se rechaza sin leer nada.</p>
 *
 * <p>Va detrás de la cadena de seguridad (que Boot registra con orden -100): quien no trae token
 * recibe su 401 mande lo que mande, y nadie sin entrar hace que el gateway guarde nada.</p>
 *
 * <p>Un formulario {@code x-www-form-urlencoded} no se guarda aquí: su cuerpo lo lee Tomcat como
 * parámetros, con su propio tope ({@code server.tomcat.max-http-form-post-size}, 2 MB), y lo
 * recompone el {@code FormFilter} del gateway.</p>
 */
@Component
@Order(RequestBodyFilter.ORDER)
@EnableConfigurationProperties(RequestBodyLimitProperties.class)
public class RequestBodyFilter extends OncePerRequestFilter {

    /** Detrás de Spring Security (-100). */
    static final int ORDER = 0;

    /** Lo que se guarda en memoria antes de pasar a un fichero temporal. */
    static final int MEMORY_THRESHOLD = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(RequestBodyFilter.class);

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RequestBodyFilter(RequestBodyLimitProperties properties, ObjectMapper objectMapper) {
        this.maxBytes = properties.maxBodySize().toBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            log.warn("Petición rechazada: su cuerpo mide {} bytes y el tope es {}", declared, maxBytes);
            writeTooLarge(request, response);
            return;
        }
        if (!hasBodyToReceive(request, declared)) {
            filterChain.doFilter(request, response);
            return;
        }

        try (ReceivedBody body = ReceivedBody.receive(request.getInputStream(), maxBytes)) {
            if (body == null) {
                log.warn("Petición rechazada: su cuerpo, sin Content-Length, pasa del tope de {} bytes", maxBytes);
                writeTooLarge(request, response);
                return;
            }
            filterChain.doFilter(new ReceivedBodyRequest(request, body), response);
        }
    }

    private static boolean hasBodyToReceive(HttpServletRequest request, long declared) {
        if (declared == 0 || (declared < 0 && request.getHeader(HttpHeaders.TRANSFER_ENCODING) == null)) {
            return false;
        }
        String contentType = request.getContentType();
        return contentType == null || !contentType.toLowerCase(Locale.ROOT)
                .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
    }

    /**
     * <i>Problem Details</i> con el tope en bytes y el identificador de correlación, como el resto de
     * errores del gateway. Lo que quede del cuerpo sin leer lo descarta Tomcat hasta
     * {@code server.tomcat.max-swallow-size}; por encima cierra la conexión.
     */
    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONTENT_TOO_LARGE,
                "El cuerpo de la petición supera el máximo que admite el gateway (" + maxBytes + " bytes).");
        problemDetail.setTitle("Content Too Large");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("maxBodySize", maxBytes);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CorrelationIdFilter.MDC_KEY, correlationId);
        }

        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }

    /** El cuerpo ya recibido: en memoria si es pequeño, en un fichero temporal si no. */
    private static final class ReceivedBody implements AutoCloseable {

        private final byte[] memory;
        private final Path file;
        private final long length;

        private ReceivedBody(byte[] memory, Path file, long length) {
            this.memory = memory;
            this.file = file;
            this.length = length;
        }

        /** Lee el cuerpo entero, o devuelve {@code null} en cuanto pasa del tope. */
        static ReceivedBody receive(InputStream input, long maxBytes) throws IOException {
            ByteArrayOutputStream memory = new ByteArrayOutputStream();
            Path file = null;
            OutputStream fileOutput = null;
            long length = 0;
            boolean complete = false;
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    length += read;
                    if (length > maxBytes) {
                        return null;
                    }
                    if (fileOutput == null && length > MEMORY_THRESHOLD) {
                        file = Files.createTempFile("mto-gateway-body-", ".tmp");
                        fileOutput = Files.newOutputStream(file);
                        memory.writeTo(fileOutput);
                        memory = null;
                    }
                    if (fileOutput != null) {
                        fileOutput.write(buffer, 0, read);
                    } else {
                        memory.write(buffer, 0, read);
                    }
                }
                complete = true;
                return new ReceivedBody(memory != null ? memory.toByteArray() : null, file, length);
            } finally {
                if (fileOutput != null) {
                    fileOutput.close();
                }
                if (!complete && file != null) {
                    Files.deleteIfExists(file);
                }
            }
        }

        InputStream open() throws IOException {
            return (memory != null) ? new ByteArrayInputStream(memory) : Files.newInputStream(file);
        }

        @Override
        public void close() {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.warn("No se ha podido borrar el fichero temporal {}", file, e);
                }
            }
        }
    }

    /** La petición con su cuerpo servido desde lo ya recibido, y con la longitud que de verdad tiene. */
    private static final class ReceivedBodyRequest extends HttpServletRequestWrapper {

        private final ReceivedBody body;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private ReceivedBodyRequest(HttpServletRequest request, ReceivedBody body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new ReceivedBodyInputStream(body.open(), body.length);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public int getContentLength() {
            return (body.length > Integer.MAX_VALUE) ? -1 : (int) body.length;
        }
    }

    private static final class ReceivedBodyInputStream extends ServletInputStream {

        private final InputStream delegate;
        private long remaining;

        private ReceivedBodyInputStream(InputStream delegate, long length) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return remaining <= 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("El cuerpo ya está recibido: se lee de forma bloqueante");
        }
    }
}
