# --- Etapa 1: build ---
# JDK 25: el pom declara <java.version>25</java.version> (Boot 4.1.1).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
# mvnw viene sin bit de ejecucion en git (100644); sin esto el build falla con exit 126
RUN chmod +x mvnw
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

# --- Etapa 2: runtime ---
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd -r -u 1001 tallerpro
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8081
ENV SERVER_PORT=8081
USER tallerpro
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
