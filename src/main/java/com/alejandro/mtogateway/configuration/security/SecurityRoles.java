package com.alejandro.mtogateway.configuration.security;

/**
 * Roles de cliente de Keycloak que el gateway comprueba, sin el prefijo {@code ROLE_} porque es el
 * formato que esperan {@code hasRole(...)} y {@code hasAnyRole(...)}.
 *
 * <p>La lista es deliberadamente corta. El gateway <b>no</b> replica la matriz de permisos de
 * negocio de {@code mto-configuration} ni la de {@code mto-stock}: quién puede leer un material o
 * borrar una estación lo sigue decidiendo el servicio que es dueño de ese dato. Mantener aquí una
 * copia de esas reglas crearía una segunda fuente de verdad que se desincronizaría con la primera
 * ruta nueva. Lo único que el gateway autoriza por su cuenta es su propio Actuator, que es suyo y de
 * nadie más.</p>
 *
 * <p>Se usan los mismos nombres que en los otros dos servicios a propósito: así un perfil de
 * operación es el mismo concepto en toda la plataforma. Eso sí, son roles <em>de cliente</em>, de
 * modo que hay que declararlos en el cliente {@code mto-gateway-api} del realm; ver README.md.</p>
 */
public final class SecurityRoles {

    private SecurityRoles() {
    }

    /** Lectura de los endpoints de operación expuestos por Actuator (métricas incluidas). */
    public static final String OPS_METRICS = "OPS_METRICS";

    /**
     * Operaciones de Actuator que <b>modifican</b> algo. Va aparte de {@link #OPS_METRICS} porque
     * son cosas distintas: leer métricas es observar, y cambiar un nivel de log en caliente altera
     * el sistema. Con un solo rol, cualquiera que pudiera consultar Prometheus podría además
     * tocarlo.
     */
    public static final String OPS_WRITE = "OPS_WRITE";
}
