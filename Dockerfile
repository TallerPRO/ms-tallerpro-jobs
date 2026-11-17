# syntax=docker/dockerfile:1
# ms-tallerpro-jobs - Spring Boot 4.1.1 / Java 25 (pom: <java.version>25</java.version>).
# Build multi-stage: el codigo fuente y Maven quedan en la etapa "build"; la imagen final
# solo contiene la JRE y el jar. Ningun secreto ni .env entra en la imagen (ver .dockerignore):
# toda la configuracion sensible llega por variables de entorno en tiempo de ejecucion.

# --- Etapa 1: build ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
# mvnw viene sin bit de ejecucion en git (100644); sin esto el build falla con exit 126.
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

# --- Etapa 2: runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app
# Usuario sin privilegios ni shell de login. El jar queda propiedad de root (solo lectura
# para la app), asi un proceso comprometido no puede reemplazarlo.
RUN groupadd --system --gid 1001 tallerpro \
 && useradd --system --uid 1001 --gid tallerpro --no-create-home --shell /usr/sbin/nologin tallerpro
COPY --from=build /workspace/target/*.jar /app/app.jar
ENV SERVER_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
USER tallerpro
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
