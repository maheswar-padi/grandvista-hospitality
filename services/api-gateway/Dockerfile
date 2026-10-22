# syntax=docker/dockerfile:1.7
# Build context: repository root  (docker build -f services/api-gateway/Dockerfile .)

# ---- build stage: compile only this service and the modules it depends on ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# All module POMs are needed for the reactor; copying them first keeps dependency resolution cacheable.
COPY pom.xml ./
COPY shared/platform-commons/pom.xml shared/platform-commons/
COPY services/discovery-server/pom.xml services/discovery-server/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/room-service/pom.xml services/room-service/
COPY services/booking-service/pom.xml services/booking-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY coverage/pom.xml coverage/
COPY shared/platform-commons/src shared/platform-commons/src
COPY services/api-gateway/src services/api-gateway/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/api-gateway -am package -DskipTests \
 && cp services/api-gateway/target/api-gateway-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime stage: JRE only, non-root, layered for small rebuilds ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER app
EXPOSE 8080
# Fewer glibc malloc arenas: lowers resident memory of a JVM with many threads by tens of MB.
ENV MALLOC_ARENA_MAX=2
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Xss512k -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
