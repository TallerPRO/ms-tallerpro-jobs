# ms-tallerpro-jobs

MS-01 de la plataforma **TallerPro**: núcleo transaccional de órdenes de servicio (EP-02).
Spring Boot 4 · Java 25 · PostgreSQL (database-per-service) · Kafka · RabbitMQ · patrón Outbox/Saga.

## Épica cubierta (EP-02 — 13 tareas)

| Tarea | Descripción | Implementado en |
|---|---|---|
| T-02.01 | Proyecto + entidades JPA (Repository Pattern) | `domain/`, `repository/` |
| T-02.02 (RF-04) | CRUD de órdenes | `controller`, `service`, `dto` |
| T-02.03 (RF-05) | Máquina de estados | `domain/EstadoOrden`, `service/EstadoOrdenValidator` |
| T-02.04 (RF-06) | Asignación mecánico/bahía | `service/OrdenServicioServiceImpl#asignarRecursos`, `service/CatalogClient` |
| T-02.05 | Consulta de estado (Cliente) | `GET /api/v1/ordenes/{id}/clientes/{clienteId}` |
| T-02.06 | Anulación de orden | `POST /api/v1/ordenes/{id}/anulacion` |
| T-02.07 | Eventos Kafka `jobs.events` | `config/KafkaConfig`, `event/JobsEventEnvelope` |
| T-02.08 | Patrón Outbox | `domain/OutboxEvent`, `service/OutboxPublisherService` |
| T-02.09 (RF-08) | Integración con ms-tallerpro-catalog | `service/CatalogClient#solicitarDisminucionStock` |
| T-02.10 | Comandos RabbitMQ (`q.cmd.email`, `q.cmd.bay`, `q.cmd.quote`) | `config/RabbitMQConfig` |
| T-02.11 | UUID como identificador | `OrdenServicio#id` (UUID) |
| T-02.12 | Dockerización | `Dockerfile`, `docker-compose.yml`, `compose-apps.snippet.yml` |
| T-02.13 | OpenAPI + tests | `config/OpenApiConfig`, `src/test/**` |

## Ejecución local

```bash
./mvnw spring-boot:run
# o con Docker (incluye PostgreSQL propio):
docker compose up --build
```

Swagger UI: `http://localhost:8081/swagger-ui.html`

## Configuración

Variables de entorno (ver `application.yml`): `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
`KAFKA_BOOTSTRAP_SERVERS`, `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `CATALOG_SERVICE_URL`.

## Tests

```bash
./mvnw test
```

Incluye tests unitarios de la máquina de estados (`EstadoOrdenTest`, `EstadoOrdenValidatorTest`) y un test
de integración del flujo completo de una orden (`OrdenServicioControllerIT`), con Kafka/RabbitMQ simulados
(mocks) y base de datos H2 en memoria.

## Seguridad (Azure AD JWT)

Cada request debe traer `Authorization: Bearer <jwt>`; el microservicio valida issuer/audience/firma por su
cuenta (confianza cero) y autoriza por rol con `@PreAuthorize` (`config/SecurityConfig`):

| Endpoint | Roles |
|---|---|
| `POST /api/v1/ordenes` | Admin, JefeTaller, Cliente |
| `GET /api/v1/ordenes`, `GET /{id}` | Admin, JefeTaller, Mecanico, Auditor |
| `GET /{id}/clientes/{clienteId}` | Admin, JefeTaller; Cliente **solo si `clienteId` == `oid` de su token** (alcance de datos) |
| `POST /{id}/asignacion`, `/entrega`, `/anulacion` | Admin, JefeTaller |
| `POST /{id}/diagnostico`, `/reparacion`, `/lista-retiro` | Admin, JefeTaller, Mecanico |

Errores: `401` sin token · `403` rol/alcance insuficiente · `404` · `409` transición inválida o recurso no disponible.

Variables: `AZURE_TENANT_ID`, `AZURE_API_CLIENT_ID`, `TALLERPRO_JWT_ENABLED` (`false` = desarrollo local con
usuario ficticio de roles `TALLERPRO_DEV_ROLES`, default `Admin,JefeTaller`).

El JWT del usuario se **propaga** a `ms-tallerpro-catalog` en cada llamada interna (`config/RestClientConfig`).

## Integración con ms-tallerpro-catalog (`service/CatalogClient`)

| Momento | Llamada |
|---|---|
| Asignar recursos (RF-06) | `GET .../bahias/{id}/disponibilidad` y luego `POST .../bahias/{id}/reserva` (409 si otra orden la tiene) |
| Diagnosticar (RF-08) | `POST .../repuestos/{id}/stock/decremento` con `eventId` (idempotente) |
| Entregar / anular | `POST .../bahias/{id}/liberacion` |

## Ejecución local sin PostgreSQL/Kafka/RabbitMQ

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test   "-Dspring-boot.run.arguments=--tallerpro.security.jwt-enabled=false"
```
(H2 en memoria; `/actuator/health` reporta `DOWN` por Kafka/Rabbit ausentes, pero la API funciona.)
