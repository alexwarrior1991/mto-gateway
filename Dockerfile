# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S mtogateway \
    && adduser -S mtogateway -G mtogateway \
    && apk add --no-cache curl

WORKDIR /app
COPY --from=build /workspace/target/mto-gateway-*.jar /app/app.jar

# Las URLs de los servicios no llevan valor por defecto a proposito: un gateway apuntando a
# localhost dentro de un contenedor arranca perfectamente y no enruta nada. Se pasan al desplegar.
ENV SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8090 \
    LOGGING_LEVEL_ROOT=INFO \
    JAVA_OPTS=""

EXPOSE 8090

USER mtogateway
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
