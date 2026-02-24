# CLAUDE.md — food-ordering-system

## Project Purpose

A Udemy course project ("Microservices: Clean Architecture, DDD, SAGA, Outbox & Kafka") demonstrating enterprise-grade microservices patterns using Spring Boot 2.6.7 and Java 17. The system processes food orders through four cooperating services that communicate exclusively over Kafka.

**Key patterns demonstrated:** Hexagonal Architecture (Ports & Adapters), Domain-Driven Design (DDD), SAGA (choreography), Outbox Pattern, CQRS, Apache Avro schema-enforced messaging.

---

## Repository Layout

```
food-ordering-system/
├── common/                  # Shared domain base classes, value objects, exceptions
├── infrastructure/          # Reusable saga, outbox, kafka producer/consumer modules
├── order-service/           # Core aggregate – Order lifecycle + SAGA orchestration  (port 8181)
├── payment-service/         # Payment debit/credit via Kafka                         (port 8182)
├── restaurant-service/      # Restaurant approval via Kafka                          (port 8183)
└── customer-service/        # Publishes CustomerCreated events                       (port 8184)
```

Every service follows the same internal structure:

```
{svc}-service/
├── {svc}-domain/
│   ├── {svc}-domain-core/          # Entities, value objects, events, domain service
│   └── {svc}-application-service/  # Ports, command handlers, DTOs, SAGA, outbox helpers
├── {svc}-dataaccess/               # JPA entities + repository adapters (outbound port impls)
├── {svc}-messaging/                # Kafka listeners + publishers (outbound port impls)
└── {svc}-container/                # Spring Boot entry point, application.yml, init-schema.sql
```

---

## Key Architecture Decisions

### Hexagonal Architecture (Ports & Adapters)
- **Ports** are interfaces defined in `{svc}-application-service/src/.../ports/input/` and `ports/output/`
- **Adapters** live in `{svc}-dataaccess` (JPA) and `{svc}-messaging` (Kafka)
- `domain-core` has **zero** Spring or infrastructure dependencies — pure Java

### Domain-Driven Design
- Base hierarchy in `common/common-domain`: `BaseId<T>` → `BaseEntity<ID>` → `AggregateRoot<ID>`
- IDs are value objects wrapping UUID/Long (e.g. `OrderId`, `CustomerId`, `RestaurantId`)
- `Money` (BigDecimal arithmetic) and `StreetAddress` are value objects — compare by value, not reference
- **Business rules belong in aggregate methods** (`Order.pay()`, `Order.approve()`, `Order.initCancel()`, etc.), not in service classes

### SAGA Pattern
- Infrastructure interface: `SagaStep<T>` in `infrastructure/saga/` — implement `process(T)` and `rollback(T)`
- Primary implementation: `OrderPaymentSaga` in `order-service/order-domain/order-application-service/`
- `SagaStatus` lifecycle: `STARTED → PROCESSING → SUCCEEDED` (happy path) or `COMPENSATING → COMPENSATED` (rollback)

### Outbox Pattern
- Domain events are saved to an outbox table **in the same DB transaction** as the state change
- `OutboxScheduler` (`@Scheduled`) polls the table and publishes to Kafka
- `OutboxCleanerScheduler` removes rows with status `COMPLETED`
- `OutboxStatus`: `STARTED → COMPLETED | FAILED`
- Outbox tables carry a `version` column — used for **optimistic locking** to prevent duplicate processing

### Kafka + Avro
- Avro schemas (`.avsc`) live in `infrastructure/kafka/kafka-model/src/main/resources/avro/`
- Run `mvn generate-sources` to regenerate Java model classes after schema changes
- **Never** let generated Avro models leak into the domain layer — use mappers to convert at the messaging adapter boundary
- Saga ID is used as the Kafka **partition key** (guarantees ordering per saga)
- Batch listener is enabled; `OptimisticLockingFailureException` in consumers is a silent NO-OP (idempotency)

### Kafka Topics
| Topic | Producer | Consumer |
|---|---|---|
| `payment-request` | Order | Payment |
| `payment-response` | Payment | Order |
| `restaurant-approval-request` | Order | Restaurant |
| `restaurant-approval-response` | Restaurant | Order |
| `customer` | Customer | Order |

### Persistence
- PostgreSQL; each service uses its own schema (`order`, `payment`, `restaurant`, `customer`)
- Schema initialised from `{svc}-container/src/main/resources/init-schema.sql` on every startup
- Reference data seeded via `init-data.sql` (payment and restaurant services)
- Connection: `localhost:5432`, user `postgres`, password `admin`

---

## Coding Conventions

### Package Naming
`com.food.ordering.system.{service}.service.{layer}.{area}`
Example: `com.food.ordering.system.order.service.domain.entity`

### Class Naming
| Kind | Pattern | Example |
|---|---|---|
| Aggregate / Entity | PascalCase noun | `Order`, `OrderItem` |
| Value Object | Semantic PascalCase | `TrackingId`, `Money`, `StreetAddress` |
| Domain Event | Noun + `Event` | `OrderCreatedEvent`, `OrderPaidEvent` |
| Command / Query DTO | Noun + `Command` / `Query` / `Response` | `CreateOrderCommand`, `TrackOrderResponse` |
| Mapper | Noun + `DataMapper` or `MessagingDataMapper` | `OrderDataMapper`, `OrderMessagingDataMapper` |
| Command Handler | Noun + `CommandHandler` | `OrderCreateCommandHandler` |
| Helper | Noun + `Helper` | `OrderSagaHelper`, `OrderCreateHelper` |
| Service interface + impl | Base name + `Impl` | `OrderDomainService` / `OrderDomainServiceImpl` |

### Lombok Rules
- **Domain aggregates and value objects**: **no Lombok** — write manual getters, private constructors, and static `builder()` inner classes
- **DTOs, commands, responses**: `@Getter @Builder @AllArgsConstructor`
- **JPA entities**: `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`
- **Service/saga/handler classes**: `@Slf4j` for logging

### Other Conventions
- `@Transactional` on all command handler methods and saga `process()` / `rollback()` methods
- JPA entity `equals`/`hashCode` based on the `id` field only
- Failure messages stored as comma-delimited `String` in entity columns (e.g. `failureMessages`)
- All exceptions extend `DomainException` (in `common-domain`); they are unchecked
- Each service has a `@ControllerAdvice` that maps domain exceptions to HTTP 400/404

---

## Common Tasks

### Debugging an Issue
1. Identify which service owns the failing step (Order → Payment → Restaurant)
2. Query the relevant outbox table — `outbox_status` and `saga_status` show exactly where the saga stalled
3. SQL logging is already on (`show-sql: true`); correlate queries with `init-schema.sql` to understand table structure
4. For Kafka delivery failures, check `@Scheduled` scheduler logs and the `KafkaMessageHelper` send callback for producer errors
5. For duplicate-processing bugs, check whether `version` optimistic locking is being handled (should be a silent NO-OP)

### Adding a Feature to an Existing Service
1. **`domain-core`** — add/modify entities, value objects, domain events, domain service logic
2. **`application-service`** — define new port interface(s); add command/handler; update `OrderDataMapper`
3. **`dataaccess`** — add JPA entity + `@Repository` + repository impl if new persistence is needed
4. **`messaging`** — add Kafka publisher or listener if new events are required
5. **`container`** — register new beans in `@Configuration`, add topic/property keys to `application.yml`
6. Update `init-schema.sql` for any new tables or columns
7. Add a unit test in `application-service/src/test/` — mock all outbound ports with Mockito

### Adding a New Kafka Message Type
1. Create an `.avsc` file in `infrastructure/kafka/kafka-model/src/main/resources/avro/`
2. Run `mvn generate-sources` in the `kafka-model` module to generate the Avro Java class
3. Add a publisher port interface under the originating service's `ports/output/message/publisher/`
4. Implement it in that service's `messaging` module using `KafkaProducer<String, NewAvroModel>`
5. Add a `@KafkaListener` consumer in the target service's `messaging` module
6. Add topic name to `application.yml` and the service's `ConfigData` class

### Adding a New Microservice
1. Mirror the module structure: `domain-core`, `application-service`, `dataaccess`, `messaging`, `container`
2. Extend `AggregateRoot<ID>` / `BaseEntity<ID>` from `common/common-domain`
3. Add the new module to the root `pom.xml` `<modules>` list
4. Use `@ConfigurationProperties` for all config — never hardcode topic names or connection strings

---

## Running Locally

**Prerequisites (Docker):** Kafka 3-node cluster (`localhost:19092,29092,39092`), Zookeeper, Confluent Schema Registry (`localhost:8081`), PostgreSQL (`localhost:5432`)

**Start order:**
1. Start Docker infrastructure
2. Run each service from its `{svc}-container` module: `mvn spring-boot:run`
3. Services auto-create their schemas on startup via `init-schema.sql`

**Try it:**
```
POST http://localhost:8181/orders   # create an order
GET  http://localhost:8181/orders/{trackingId}  # track order status
```

---

## Testing
 
- **Integration tests**: `@SpringBootTest(classes = {Svc}Application.class)` + `@Sql` for setup/teardown
- Key example: `OrderPaymentSagaTest` — uses `CountDownLatch` to test concurrent saga execution and optimistic locking
- **Unit tests**: no Spring context; mock all outbound ports (repositories, publishers) with Mockito
- Test SQL files live under `{svc}-container/src/test/resources/sql/`
- Test `application.yml` overrides live under `{svc}-application-service/src/test/resources/`
