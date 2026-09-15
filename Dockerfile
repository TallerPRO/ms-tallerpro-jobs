# --- Etapa 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN mvn -B -q dependency:go-offline || true
COPY src src
RUN mvn -B -q clean package -DskipTests

# --- Etapa 2: runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S tallerpro && adduser -S tallerpro -G tallerpro
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8081
ENV SERVER_PORT=8081
USER tallerpro
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
