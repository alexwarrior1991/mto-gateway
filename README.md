# mto-gateway

Puerta de entrada única a las APIs de infraestructura ferroviaria del dominio `MTO`. Es un API
Gateway construido con **Spring Cloud Gateway Server WebMVC** (el sabor servlet, no el reactivo)
sobre Spring Boot 4 y Java 25.

El gateway **no persiste nada** y **no decide permisos de negocio**. Lo que hace es:

- publicar un contrato de rutas estable y desacoplado de los caminos internos de cada servicio,
- autenticar en el borde (firma, emisor y vigencia del JWT del realm),
- dar a cada petición un identificador de correlación y propagarlo,
- resolver CORS para los clientes web,
- aislar fallos con timeouts y circuit breaker,
- exponer su propia salud y sus métricas.

Servicios detrás:

| Servicio | Qué expone |
|---|---|
| [`mto-configuration`](../mto-configuration) | Infraestructura ferroviaria: líneas, tramos, estaciones, vías, perfiles, ménsulas, seccionadores, paquetes de ejecución y los catálogos técnicos (LOV). |
| [`mto-stock`](../mto-stock) | Inventario: materiales, almacenes, movimientos, reservas, proyectos y conjuntos (BOM). |

---

## Rutas

| Ruta pública | Destino | Ruta interna | Circuit breaker |
|---|---|---|---|
| `/api/configuration/actuator/**` | `MTO_CONFIGURATION_URL` | `/actuator/**` | no |
| `/api/configuration/**` | `MTO_CONFIGURATION_URL` | `/api/v1/configuration/**` | `mtoConfiguration` |
| `/api/stock/actuator/**` | `MTO_STOCK_URL` | `/actuator/**` | no |
| `/api/stock/**` | `MTO_STOCK_URL` | `/api/v1/inventory/**` | `mtoStock` |
| `/actuator/**` | *el propio gateway* | — | — |

Tres detalles que evitan sorpresas:

1. **`/actuator/**` sin prefijo es el gateway**, no un servicio. La salud de `mto-stock` se pregunta
   en `/api/stock/actuator/health`; la del gateway, en `/actuator/health`.
2. **`stock` de puertas afuera es `inventory` de puertas adentro.** El servicio sirve en
   `/api/v1/inventory`; el nombre público lo fija el gateway. Que el servicio se llame por dentro de
   otra forma deja de ser un detalle que tengan que conocer sus clientes.
3. **El prefijo público no lleva versión** a propósito. `/api/configuration` es el contrato con el
   cliente y `/api/v1/configuration` es un detalle interno. El día que salga una `v2`, el cambio es
   una línea del YAML y no una migración de todos los clientes.

### Por qué `RewritePath` y no `StripPrefix`

Los prefijos público e interno no coinciden. `StripPrefix` solo **quita** segmentos de cabecera:
`/api/stock/materials` con `StripPrefix=2` queda en `/materials`, y el servicio sirve en
`/api/v1/inventory/materials`. Habría que encadenar `StripPrefix=2` + `PrefixPath=/api/v1/inventory`,
dos filtros cuyo orden relativo no se lee en el YAML. `RewritePath` hace el cambio completo en una
sola expresión:

```yaml
- RewritePath=/api/stock(?<segment>.*), /api/v1/inventory$\{segment}
```

Dos cosas de esa línea son deliberadas:

- **La barra invertida de `$\{segment}`.** Sin ella, el resolutor de placeholders de Spring
  intentaría resolver `${segment}` como una property al cargar el YAML. El propio filtro deshace el
  escape antes de aplicar el reemplazo.
- **El grupo va pegado al prefijo**, sin `/` delante, para que la barra separadora viaje dentro del
  grupo. Así `/api/stock` a secas se reescribe a `/api/v1/inventory` y no a `/api/v1/inventory/`.

### Añadir un servicio ferroviario nuevo

Son **dos bloques de YAML y ninguna línea de Java**. La correlación, el CORS, la seguridad y los
timeouts son globales y no saben qué rutas existen.

1. En `application.yaml`, añade el destino:

   ```yaml
   app:
     services:
       signalling:
         url: ${MTO_SIGNALLING_URL:http://localhost:8083}
   ```

2. Añade sus dos rutas: la de Actuator con un `order` bajo y la de la API con uno alto.

   ```yaml
   - id: mto-signalling-actuator
     uri: ${app.services.signalling.url}
     order: 2
     predicates: [ Path=/api/signalling/actuator/** ]
     filters:
       - RewritePath=/api/signalling/actuator(?<segment>.*), /actuator$\{segment}

   - id: mto-signalling-api
     uri: ${app.services.signalling.url}
     order: 12
     predicates: [ Path=/api/signalling/** ]
     filters:
       - RewritePath=/api/signalling(?<segment>.*), /api/v1/signalling$\{segment}
       - name: CircuitBreaker
         args:
           id: mtoSignalling
           fallbackUri: forward:/fallback/signalling
   ```

   > El `CircuitBreaker` va en **forma extendida** (`name:` + `args:`) a propósito. La abreviada
   > `CircuitBreaker=name=mtoSignalling,fallbackUri=...` se interpreta **por posición**: el
   > identificador del circuito acaba siendo la cadena literal `name=mtoSignalling` y el fallback se
   > pierde, sin que nada falle al arrancar. El síntoma es un 500 con la traza del proxy en lugar del
   > 503.

3. Declara su instancia en `resilience4j.circuitbreaker.instances`, añade `MTO_SIGNALLING_URL` a
   `.env.example` y a la tabla de este README, y añade un caso a `GatewayRoutingIntegrationTest`.

Las sondas de salud del servicio nuevo quedan abiertas sin tocar nada: la regla de seguridad usa el
comodín `/api/*/actuator/health`. Lo que sí se declara en `FallbackController.SERVICES` es su nombre
legible y el id de su circuito, que es de donde sale el `Retry-After` del 503; un servicio que no
esté en ese mapa tampoco rompe nada — se usa el segmento de la URL como nombre y la configuración por
defecto del registro para la espera.

---

## Variables de entorno

Todas tienen valor por defecto salvo donde se indique. Copia `.env.example` a `.env` para
partir de una base.

| Variable | Por defecto | Para qué |
|---|---|---|
| `SERVER_PORT` | `8090` | Puerto del gateway |
| `SPRING_PROFILES_ACTIVE` | *(ninguno)* | `local` o `docker` |
| `MTO_CONFIGURATION_URL` | `http://localhost:8081` | Destino de `/api/configuration/**` |
| `MTO_STOCK_URL` | `http://localhost:8080` | Destino de `/api/stock/**` |
| `GATEWAY_CONNECT_TIMEOUT` | `2s` | Abrir el socket contra el servicio |
| `GATEWAY_READ_TIMEOUT` | `15s` | Tiempo máximo sin recibir bytes |
| `GATEWAY_CIRCUIT_BREAKER_TIMEOUT` | `20s` | Red de seguridad de Resilience4j |
| `KEYCLOAK_ISSUER_URI` | `http://auth.mto.local:8082/realms/mto` | Realm que emite los tokens |
| `KEYCLOAK_CLIENT_ID` | `mto-gateway-api` | Cliente del que se leen los roles de operación |
| `KEYCLOAK_AUDIENCE_VALIDATION_ENABLED` | `false` | Ver [Seguridad](#seguridad) |
| `KEYCLOAK_AUDIENCE` | *(vacío)* | Obligatoria si la anterior es `true` |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:5173` | Lista separada por comas, sin comodines |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | `framework` solo detrás de un ingress de confianza |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,prometheus` | Endpoints de Actuator publicados |
| `MTO_TRACING_ENABLED` | `true` | Trazado distribuido |
| `MTO_TRACING_SAMPLING_PROBABILITY` | `0.1` | Fracción de trazas muestreadas |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Colector OTLP |
| `SPRING_THREADS_VIRTUAL_ENABLED` | `true` | Hilos virtuales para el proxy bloqueante |

> **Aviso sobre puertos.** En local, `mto-stock` escucha en el **8080**, `mto-configuration` en el
> **8081** y Keycloak en el **8082**. Por eso el gateway usa el 8090 y `MTO_STOCK_URL` apunta al
> 8080. Un gateway apuntando al 8082 arrancaría sin quejarse y devolvería 404 del proveedor de
> identidad, que se parecen mucho a un fallo de enrutado.

---

## Arranque en local

Requisitos: JDK 25 y los dos servicios en marcha (o cualquier cosa que responda en sus URLs).

```bash
# Con los valores por defecto
./mvnw spring-boot:run

# Con el perfil local: log de enrutado en DEBUG, que es lo que se quiere mientras se ajustan rutas
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run

# Apuntando a otros destinos
MTO_CONFIGURATION_URL=http://localhost:9001 \
MTO_STOCK_URL=http://localhost:9002 \
./mvnw spring-boot:run
```

En Windows PowerShell, `./mvnw.cmd` en lugar de `./mvnw`.

Build y tests:

```bash
./mvnw verify                                   # compila y pasa toda la suite
./mvnw test -Dtest=GatewayRoutingIntegrationTest # solo el enrutado
```

La suite **no necesita Docker**: el servicio de destino de las pruebas de enrutado es el
`HttpServer` del propio JDK en loopback.

### Con Docker

Lo habitual es levantarlo desde [`mto-platform`](../mto-platform), que trae la infraestructura
compartida del dominio y la imagen ya publicada:

```bash
cd ../mto-platform
docker compose --profile all up -d
./keycloak/apply-partials.sh
```

Para probar una construcción local del gateway contra esa misma infraestructura:

```bash
cp .env.example .env
docker compose up -d --build
```

`compose.yaml` levanta solo el gateway: no persiste nada y no tiene infraestructura propia.

---

## Llamadas de ejemplo

```bash
# Salud del propio gateway. No pide token.
curl -i http://localhost:8090/actuator/health

# Salud de cada servicio a traves del gateway. Tampoco piden token: comprobar que el enrutado
# funciona no deberia exigir montar Keycloak.
curl -i http://localhost:8090/api/configuration/actuator/health
curl -i http://localhost:8090/api/stock/actuator/health

# Una llamada de negocio. Sin token, 401 del gateway.
curl -i http://localhost:8090/api/stock/materials

# Con token. Llega a mto-stock como /api/v1/inventory/materials.
curl -i -H "Authorization: Bearer $TOKEN" \
     http://localhost:8090/api/stock/materials

# El identificador de correlacion, de ida y vuelta.
curl -i -H 'X-Correlation-Id: probe-123' \
     http://localhost:8090/api/stock/actuator/health
# ... la respuesta trae 'X-Correlation-Id: probe-123' y mto-stock ha visto ese mismo valor.
```

---

## El identificador de correlación

Cada petición lleva un `X-Correlation-Id` que aparece en tres sitios: la cabecera que sale hacia el
servicio, la cabecera de la respuesta y cada línea de log del gateway (`%X{correlationId}`). Es lo
que permite seguir una petición desde el navegador hasta `mto-stock` y volver.

- Si la petición ya trae uno **válido**, se conserva.
- Si no trae ninguno, o el que trae no es válido, se genera un UUID.
- **Válido** significa `[A-Za-z0-9._-]` y como mucho 64 caracteres (`app.correlation.max-length`).
  La validación no es cosmética: ese valor acaba escrito en los logs de tres servicios y en una
  cabecera saliente, de modo que un CR/LF permitiría partir la línea e inventarse entradas de log, o
  colar una cabecera entera contra el servicio de destino.
- Un valor inválido **se sustituye, no se rechaza**. Devolver un 400 convertiría una cabecera de
  traza —opcional y meramente informativa— en un motivo para tirar la petición.

Va implementado como filtro de servlet (`CorrelationIdFilter`) y no como filtro de ruta: el gateway
en su sabor servlet no tiene `default-filters`, así que un filtro de ruta habría que repetirlo en
cada bloque del YAML, no podría *generar* un valor, y no cubriría lo que nunca llega a enrutarse (el
Actuator del propio gateway, los *preflight*, los 404 sin ruta y los 401 de la cadena de seguridad).

---

## Seguridad

El gateway es el **borde de autenticación** y los servicios siguen siendo los dueños de la
**autorización**:

- El gateway comprueba que el token está firmado por el realm, que lo emitió quien dice y que sigue
  vigente, y rechaza el tráfico anónimo antes de gastar una conexión contra un servicio.
- Quién puede leer qué y quién puede borrar qué lo sigue decidiendo `mto-configuration` y
  `mto-stock`, cada uno sobre sus datos. Duplicar aquí su matriz de roles crearía una segunda fuente
  de verdad que se desincronizaría con la primera ruta nueva.
- La cabecera `Authorization` viaja **intacta** aguas abajo. No se usa `TokenRelay`, que cambiaría el
  token del usuario por el del gateway: los dos servicios auditan a la persona, no al proxy.

**No hay un interruptor para apagar la seguridad**, igual que en los otros dos repositorios: lo que
cambia entre entornos son las properties, no la existencia de la cadena de filtros. Con
`spring-boot-starter-security` en el classpath, un flag de ese tipo tampoco desactivaría nada —
quedarse sin `SecurityFilterChain` propio devuelve el control a la cadena por defecto de Boot, con
formulario de login, CSRF y sesiones delante de una API.

Abiertos sin token: `OPTIONS /**` (el *preflight* viaja sin `Authorization` por definición),
`/actuator/health`, `/actuator/health/**`, `/actuator/info`, `/fallback/**`, `/error` y las sondas de
los servicios (`/api/*/actuator/health`, `/api/*/actuator/info`). Esto último no abre nada nuevo: los dos
servicios ya sirven `/actuator/health` sin autenticar y con `show-details: when-authorized`.

El resto de `/actuator/**` del gateway —`prometheus` incluido, que enumera las rutas internas— pide
el rol de cliente `ops-metrics` (`ops-write` para POST y DELETE), los mismos nombres que usan los
otros dos servicios. Son roles **de cliente**, así que hay que declararlos en el cliente
`mto-gateway-api` del realm.

### Por qué la audiencia no se valida aquí

Las audiencias son **por servicio**: `mto-configuration` exige `aud ⊇ {mto-configuration-api}` y
`mto-stock` exige `aud ⊇ {mto-stock-api}`. Ningún token que circula hoy lleva una audiencia del
gateway. Si el gateway exigiera la suya, rechazaría tokens que los servicios sí aceptan; y si además
dejara pasar uno sin la audiencia del destino, el 401 llegaría **después** del gateway, que es el
fallo más confuso posible.

Por eso `KEYCLOAK_AUDIENCE_VALIDATION_ENABLED=false` de serie. El requisito previo —que el
*audience mapper* de `mto-frontend` emita `mto-gateway-api` junto a `mto-configuration-api` y
`mto-stock-api` en el mismo token— **ya está puesto** en el realm base de `mto-platform`, así que
activarla es solo cambiar dos variables:

```bash
KEYCLOAK_AUDIENCE_VALIDATION_ENABLED=true KEYCLOAK_AUDIENCE=mto-gateway-api ./mvnw spring-boot:run
```

Ponerla a `true` con `KEYCLOAK_AUDIENCE` vacío **no arranca**: un `@AssertTrue` de
`SecurityProperties` lo impide, porque una audiencia vacía apagaría la validación en tiempo de
petición y sin dejar rastro en el log.

El `JwtDecoder` se construye con el **JWK Set** y no con el descubrimiento por emisor: el
descubrimiento es una llamada HTTP bloqueante al crear el bean, así que el gateway no arrancaría si
Keycloak todavía no está listo. Con el JWK Set la descarga es perezosa y un reinicio simultáneo de la
plataforma deja de ser un fallo de arranque.

### El cliente en Keycloak

El gateway aporta su propio cliente al realm con `keycloak/mto-gateway-partial-import.json`, igual
que hacen `mto-configuration` y `mto-stock` con los suyos. No va dentro del realm base: ese fichero
es el que **crea** el realm, y un compuesto que nombre un cliente que aún no existe aborta la
importación entera.

| Qué | Dónde |
|---|---|
| Cliente `mto-gateway-api` y sus roles `ops-metrics` / `ops-write` | `keycloak/mto-gateway-partial-import.json`, en **este** repo |
| `mto-ops` ampliado para cubrir también el gateway | `keycloak/mto-ops-cross-service.json`, en **`mto-platform`** |
| *Audience mapper* de `mto-frontend` hacia `mto-gateway-api` | `keycloak/mto-realm.json`, en **`mto-platform`** |

Lo aplica todo, en orden, el guion de la plataforma:

```bash
cd ../mto-platform && ./keycloak/apply-partials.sh
```

El orden importa: `mto-ops-cross-service.json` nombra `mto-gateway-api`, así que el cliente tiene
que existir antes. Sin ese paso, `/actuator/prometheus` del gateway queda cerrado incluso para quien
tiene el perfil `mto-ops` — una importación parcial **reescribe el rol entero**, de modo que el
fichero cross-service repite los permisos de los tres servicios y aplicarlo es lo que los junta.

`mto-platform/scripts/check_realm_consistency.py` recorre todos los ficheros en el orden en que se
aplican y falla si alguno nombra un cliente o un rol que todavía no existe.

---

## CORS

El gateway en su sabor servlet **no trae CORS propio**: sus rutas son `RouterFunction` de Spring MVC,
así que lo que aplica es el CORS de Spring Web de siempre, mediante un `CorsConfigurationSource` que
recoge `http.cors(...)`. La política se configura en `app.security.cors`, con el mismo esquema que en
los otros dos repositorios.

La cabecera de correlación **no se enumera** en `allowed-headers` ni en `exposed-headers`: la añade
`SecurityConfiguration` leyendo `app.correlation.header-name`, de modo que su nombre vive en un solo
sitio. El gateway *pone* esa cabecera en cada respuesta, así que tiene que aceptarla y exponerla sea
cual sea su nombre — y sin exponerla, el navegador la ve llegar y no deja leerla desde JavaScript,
justo a quien tiene que pegarla en un informe de error.

`allowed-origins` **no admite el comodín** y la aplicación no arranca si se pone: con
`allow-credentials` activo Spring lo rechaza en tiempo de ejecución, de modo que un `"*"` puesto para
salir del paso rompería cada *preflight* en vez de aflojar la política, y el fallo aparecería en la
consola del navegador, lejos de quien escribió la configuración.

---

## Timeouts y resiliencia

- Los timeouts se configuran con `spring.http.clients.connect-timeout` y `.read-timeout` — las claves
  en plural de Boot 4.1; las `spring.http.client.*` en singular están deprecadas. El proxy del
  gateway es un `RestClient` sobre el `ClientHttpRequestFactory` de Boot, así que es ahí y no bajo
  `spring.cloud.gateway` donde se ajustan.
- Cada ruta de API lleva un `CircuitBreaker` de Resilience4j con su `fallbackUri`. Con el circuito
  abierto, la respuesta es un **503** con `Retry-After` y un cuerpo `application/problem+json` que
  dice qué servicio concreto no está disponible, en vez de un 500 con la traza del proxy dentro.
  Las rutas de Actuator **no** llevan circuit breaker: son la sonda con la que se comprueba si el
  servicio está vivo, y taparla con un fallback es quedarse sin la única respuesta útil.
- El `Retry-After` de ese 503 **se lee de la configuración real del circuito**
  (`wait-duration-in-open-state`), no va escrito en el código: es el tiempo que va a tardar en dejar
  pasar la primera llamada de prueba, así que reintentar antes solo suma peticiones rechazadas.
- `GATEWAY_CIRCUIT_BREAKER_TIMEOUT` es mayor que el `read-timeout` a propósito: así gana el timeout
  HTTP y el error que se registra es el real del servicio, no una cancelación genérica. **Ojo:** el
  valor por defecto del `TimeLimiter` de Spring Cloud CircuitBreaker es de **1 segundo**, y sin
  sobreescribirlo cualquier consulta que pase de un segundo acabaría en el fallback.

> **Streaming.** `read-timeout` es un tiempo máximo *sin recibir bytes*, no un presupuesto total de
> la petición. Un SSE o un *long-poll* que se quede callado más de ese tiempo se corta por la mitad.
> Hoy no afecta a nada: los trabajos largos de `mto-configuration` devuelven `202` al momento y el
> cliente consulta el estado. Si algún día se publica un endpoint de streaming, la solución no es
> subir el timeout global —eso costaría el fallo rápido en todas las llamadas normales—, sino darle
> su propia ruta con su cliente.

---

## Trazado distribuido

El gateway exporta trazas a un colector **OTLP** con el mismo montaje que `mto-configuration`
(`spring-boot-starter-opentelemetry`, que en Boot 4 sustituye al par
`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`).

Aquí importa más que en ningún otro servicio: **el gateway es el borde, o sea el único sitio donde
una traza debería empezar.** Sin esto, cada servicio abría su propia traza y no había forma de ver
una petición de punta a punta.

Qué hace en concreto:

- Si la petición **no** trae `traceparent`, el gateway abre la traza y lo inyecta hacia el servicio
  de destino.
- Si **sí** lo trae, la continúa: mismo `trace-id`, `span-id` nuevo para el salto por el gateway.
- La cabecera sale **una sola vez**. No es una obviedad: el proxy copia todas las cabeceras
  entrantes y además la instrumentación del `RestClient` inyecta la suya, así que un `traceparent`
  duplicado —que dejaría al servicio de destino eligiendo cuál respeta— era un desenlace posible.
  Lo fija `GatewayTracingIntegrationTest`.
- Cada línea de log lleva los tres identificadores:
  `[mto-gateway,<traceId>,<spanId>,<correlationId>]`.

Dos cosas que conviene saber:

> **El muestreo bajo no rompe la cadena.** Con `MTO_TRACING_SAMPLING_PROBABILITY=0.1` solo se
> registra el 10% de las trazas, pero un span **no** muestreado sigue propagando su `traceparent`
> con el flag `00`, así que la correlación entre servicios se mantiene. Muestrear el 100% en un
> servicio con tráfico real es caro y casi nunca hace falta.

> **La cadena está completa.** Los tres servicios llevan ya el mismo montaje, así que una petición
> se sigue entera: gateway → `mto-configuration` → (cola de RabbitMQ) → `mto-stock`. El salto por la
> cola también: `mto-configuration` escribe `traceparent`/`tracestate` en las cabeceras AMQP al
> publicar desde el outbox, y el consumidor de `mto-stock` los continúa
> (`spring.rabbitmq.listener.simple.observation-enabled`).

### Dónde se ven las trazas

El colector es un **Jaeger** *all-in-one*. Habla OTLP de forma nativa, así que no hace falta un
OpenTelemetry Collector delante: los tres servicios exportan directamente a él. Los stacks de
`mto-configuration` y de `mto-stock` traen cada uno el suyo, para poder levantarse solos igual que
cada uno trae su Keycloak; el gateway no lleva ninguno porque su `compose.yaml` es solo el gateway.

| | |
|---|---|
| Interfaz web | http://localhost:16686 |
| Ingesta OTLP por HTTP | `4318` — la que usan los tres servicios |
| Ingesta OTLP por gRPC | `4317` |

Los tres lo alcanzan por el mismo nombre, **`otel.mto.local`**, resuelto por el host con
`extra_hosts` — el mismo idioma que ya se usaba con Keycloak (`auth.mto.local`). Los dos Jaeger
chocan en los mismos puertos del host si se levantan los dos stacks, exactamente como ya chocan
Postgres, RabbitMQ y Keycloak, y **eso es justo lo que hace que funcione**: el que se quede con el
puerto recibe todas las trazas, así que una petición que empieza aquí y termina en `mto-stock` se ve
entera en un solo visor. Un colector alcanzable solo dentro de cada stack dejaría cada traza
distribuida partida entre visores distintos.

Fuera de compose (`mvnw spring-boot:run`) ese nombre no resuelve: ahí vale el valor por defecto,
`http://localhost:4318/v1/traces`, con el Jaeger publicado en el host.

Las trazas **no se persisten**: Jaeger las guarda en memoria y se pierden al parar el contenedor.
Persistirlas pediría Elasticsearch o Cassandra detrás, que es mucho aparato para mirar una traza
mientras se depura.

Las métricas OTLP van **apagadas** (`management.otlp.metrics.export.enabled: false`): el starter
arrastra también un registro OTLP de métricas y, sin eso, el gateway las empujaría a un colector
*además* de exponerlas en `/actuator/prometheus`. Misma decisión que en `mto-configuration`.

El `X-Correlation-Id` **no** se sustituye por la traza y las dos identidades conviven: el de
correlación es legible, lo puede teclear una persona en una incidencia, y sobrevive a que el trazado
esté apagado o a que el span no se muestree. Tampoco se propaga como *baggage* de OpenTelemetry —la
plataforma no usa baggage en ningún sitio, así que hacerlo aquí sería inventar comportamiento nuevo.

---

## Observabilidad

- `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`, `/actuator/info` y
  `/actuator/prometheus`, el mismo juego que los otros dos servicios, para que un solo *job* de
  Prometheus recoja los tres.
- El estado de los circuitos aparece en `/actuator/health` como componente informativo, pero **no**
  decide el estado global: un circuito abierto significa que `mto-stock` está mal, no el gateway, y
  no debe sacarlo del balanceador.
- El perfil `local` sube a `DEBUG` el log de `org.springframework.cloud.gateway`, que publica la
  tabla de rutas al arrancar y el detalle de qué ruta encaja con cada petición. Es lo primero que hay
  que mirar cuando algo devuelve 404 y no se sabe si el 404 es del gateway o del servicio.
- No existe un endpoint `/actuator/gateway` en el sabor servlet; el del gateway reactivo no aplica
  aquí.

---

## CI y despliegue

`.github/workflows/ci.yml` tiene dos trabajos:

- **`test`** — en cada `push`, `pull_request` y `workflow_dispatch`: `checkout` → JDK 25 (temurin,
  con caché de Maven) → `./mvnw -B --no-transfer-progress verify` → subida de los informes de
  Surefire. Es la misma forma que el `ci.yml` de `mto-configuration` y `mto-stock`.
- **`image`** — construye la imagen con Buildx **siempre**, también en los *pull request*. Antes solo
  se construía al entrar en `master`, de modo que un `Dockerfile` roto no se descubría hasta después
  de fusionar, justo cuando ya no hay nadie mirando.
  - En un PR la imagen no se publica: se carga en el demonio local del runner y se le pasa un
    **smoke test** que la arranca sin ningún servicio detrás y espera a que `/actuator/health`
    responda. Construir no prueba que el contenedor arranque; esto sí, y de paso comprueba que el
    gateway levanta sin base de datos, sin Keycloak y sin destinos.
  - En `push` a `master` se publica en **GHCR** como `ghcr.io/<owner>/mto-gateway`, con las
    etiquetas `sha-<corto>` y `latest`. Se autentica con el `GITHUB_TOKEN` del propio workflow
    (`permissions: packages: write`), así que **no hay ningún secreto que dar de alta**.

### Promocionar una imagen

`.github/workflows/promote.yml` se lanza a mano (*Actions → Promote image → Run workflow*) y le pone
una etiqueta estable a una imagen que ya existe:

| Entrada | Por defecto | Qué es |
|---|---|---|
| `source_tag` | `latest` | La etiqueta que se quiere promocionar, normalmente un `sha-<corto>` concreto |
| `target_tag` | `stable` | El nombre fijo al que apunta quien despliega |

Usa `docker buildx imagetools create`, que **copia el manifiesto y no las capas**: no descarga ni
reconstruye nada, así que lo que queda etiquetado es exactamente el mismo digest que se probó.
Reconstruir desde el mismo commit no daría esa garantía. El resumen del workflow deja escrito el
digest, quién promocionó y el `docker pull` listo para copiar.

No hay paso de despliegue automático: no hay todavía un entorno al que desplegar. El día que lo
haya, se engancha en ese workflow — el digest ya está resuelto y elegido a mano, que es justo la
parte que no conviene automatizar a ciegas. Para desplegar mientras tanto:

```bash
# Por digest y no por etiqueta: la etiqueta se mueve, el digest no.
docker pull ghcr.io/<owner>/mto-gateway@sha256:<digest>
docker run -d --name mto-gateway -p 8090:8090 \
  -e MTO_CONFIGURATION_URL=http://mto-configuration-api:8080 \
  -e MTO_STOCK_URL=http://mto-stock-app:8080 \
  -e KEYCLOAK_ISSUER_URI=https://auth.example.com/realms/mto \
  -e APP_CORS_ALLOWED_ORIGINS=https://mto.example.com \
  ghcr.io/<owner>/mto-gateway@sha256:<digest>
```

La primera vez, el paquete de GHCR nace privado: hay que darle acceso al repositorio o hacerlo
público desde *Packages* en GitHub.

---

## Estructura

```
src/main/java/com/alejandro/mtogateway/
├── MtoGatewayApplication.java
├── configuration/security/
│   ├── SecurityConfiguration.java              cadena de filtros, CORS y JwtDecoder
│   ├── SecurityProperties.java                 app.security (record @Validated)
│   ├── SecurityRoles.java                      ops-metrics / ops-write
│   ├── JwtAudienceValidator.java               copia literal de la de los otros dos servicios
│   ├── KeycloakJwtAuthenticationConverter.java claims -> autoridades
│   └── GatewayAuthenticationErrorHandler.java  401 y 403 como problem+json
├── filter/
│   ├── CorrelationIdFilter.java                X-Correlation-Id
│   └── CorrelationIdProperties.java            app.correlation
└── controller/
    └── FallbackController.java                 503 cuando el circuito está abierto

keycloak/
└── mto-gateway-partial-import.json             cliente mto-gateway-api y sus roles de operación

src/main/resources/
├── application.yaml                            rutas, timeouts, seguridad, CORS, resiliencia
├── application-local.yaml                      log de enrutado en DEBUG
└── application-docker.yaml                     destinos por nombre de servicio

src/test/java/com/alejandro/mtogateway/
├── MtoGatewayApplicationTests.java             arranque sin base de datos, rutas enlazadas
├── CorrelationIdFilterTest.java                el filtro, sin contexto de Spring
├── GatewayRoutingIntegrationTest.java          enrutado, correlación, seguridad y CORS de verdad
├── GatewayTracingIntegrationTest.java          la traza nace y se propaga una sola vez
├── GatewayCorsHeaderNameTest.java              CORS sigue a app.correlation.header-name
└── GatewayFallbackIntegrationTest.java         qué ve el cliente con el servicio caído
```
