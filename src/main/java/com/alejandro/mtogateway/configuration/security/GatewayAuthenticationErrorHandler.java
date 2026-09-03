package com.alejandro.mtogateway.configuration.security;

import com.alejandro.mtogateway.filter.CorrelationIdFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Respuesta del gateway a un 401 y a un 403, en dos mitades que resuelve cada una quien sabe
 * hacerlo.
 *
 * <p>La cabecera {@code WWW-Authenticate} la construyen los manejadores de Spring Security, que
 * siguen el RFC 6750: distinguen «no has traído token» de «tu token no vale» y, en el segundo caso,
 * añaden {@code error} y {@code error_description}. Sin esa cabecera el cliente no puede saber si le
 * toca refrescar el token o volver a autenticar, y acaba tratando cualquier 401 igual.</p>
 *
 * <p>El cuerpo se escribe aquí como <i>Problem Details</i> (RFC 9457), igual que el de los dos
 * servicios de detrás, para que un cliente no necesite dos <i>parsers</i> según quién le haya
 * rechazado. En {@code mto-configuration} lo escribe un {@code @ControllerAdvice} a través del
 * {@code HandlerExceptionResolver}; el gateway no tiene controladores de negocio ni ese advice, así
 * que lo serializa directamente.</p>
 *
 * <p>Se implementan las dos interfaces en la misma clase porque el gateway responde igual a ambos
 * casos salvo por el estado: separarlas serían dos ficheros con el mismo cuerpo.</p>
 */
@Component
public class GatewayAuthenticationErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerTokenEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerTokenAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

    private final ObjectMapper objectMapper;

    public GatewayAuthenticationErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {
        bearerTokenEntryPoint.commence(request, response, authException);
        writeProblemDetail(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException);
        writeProblemDetail(request, response, HttpStatus.FORBIDDEN, "Forbidden");
    }

    /**
     * El detalle es genérico a propósito: decirle a quien no ha podido autenticarse qué le faltaba
     * exactamente es ayudar a quien está probando. Lo concreto queda en el log, localizable por el
     * identificador de correlación que va tanto en la cabecera como aquí en el cuerpo — es lo único
     * que hay que pedirle al cliente para encontrar su petición.
     */
    private void writeProblemDetail(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String title
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, title);
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CorrelationIdFilter.MDC_KEY, correlationId);
        }

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
