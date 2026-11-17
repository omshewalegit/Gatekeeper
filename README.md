# Gatekeeper — Distributed Multi-Tenant API Gateway Platform

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Security](https://img.shields.io/badge/Spring_Security-6.x-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white)](https://spring.io/projects/spring-security)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-4.4.0-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-8.0-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

**Gatekeeper** is a distributed, production-grade API Gateway and Management Platform built with Java and Spring Boot. It provides a full-featured **Control Plane** for multi-tenant administration and an ultra-fast **Data Plane** for dynamic request routing, distributed sliding-window rate limiting, write idempotency, and asynchronous telemetry streaming.

---

## 🏛️ System Architecture

Gatekeeper implements a clean separation of concerns between the **Control Plane** and the **Data Plane**:

```
                       +-----------------------------------+
                       |        Client Application         |
                       +-----------------+-----------------+
                                         |
                                         | HTTP Traffic (/{orgSlug}/**)
                                         v
                            +--------------------------+
                            |   smart-gateway-service  | (Data Plane :8080)
                            |                          |
                            |  - In-Memory Registry    |
                            |  - Sliding-Window Rate   |
                            |  - POST Idempotency      |
                            +-----+--------------+-----+
                                  |              |
                      Redis Keys  |              | Kafka Telemetry
                                  v              v
                           +-------------+   +-------------------+
                           | Redis Store |   |  Kafka Cluster    |
                           +-------------+   +---------+---------+
                                                       |
                                                       | Event Stream
                                                       v
                                            +---------------------+
                                            |  analytics-service  | (:8082)
                                            |  - Telemetry Sink   |
                                            |  - MongoDB Datastore|
                                            +---------------------+

       +-------------------------------------------------------------+
       |                        CONTROL PLANE                        |
       |                                                             |
       |  +------------------------------+   Route Lifecycle Events  |
       |  |    api-management-service    |---------------------------+
       |  |    (Port :8085)              |   (Topic: gateway-config-events)
       |  |  - Tenant Administration     |
       |  |  - Route Versioning & Audit  |
       |  |  - RBAC & JWT Authentication |
       |  +--------------+---------------+
       |                 |
       |                 v
       |          +--------------+
       |          | MySQL RDBMS  |
       |          +--------------+
       +-------------------------------------------------------------+
```

### Key Architectural Highlights
1. **Control-Plane / Data-Plane Separation**: Administrative CRUD operations are strictly decoupled from runtime request processing to ensure that control plane outages do not disrupt live gateway routing.
2. **Zero-Downtime Route Synchronization**: Route lifecycle changes committed to MySQL fire a `@TransactionalEventListener(AFTER_COMMIT)` which publishes an event to Apache Kafka. The Smart Gateway consumes this event and hot-reloads its in-memory compiled route registry via `AtomicReference`.
3. **Atomic Sliding-Window Rate Limiter**: Rate limits are enforced using Redis Sorted Sets (`ZSET`) and Lua scripts, guaranteeing atomic sliding-window throttling per client IP and route.
4. **Distributed Request Idempotency**: Prevents duplicate executions of critical write mutations (e.g., payments, orders) by caching downstream responses in Redis keyed by client-supplied `Idempotency-Key` headers.
5. **Multi-Tenant RBAC & Auditing**: Platform and Tenant Administrator separation using stateless JWT tokens, Flyway schema migrations, route snapshot versioning, and append-only control plane audit records.

---

## 🧩 Microservices Overview

### 1. `api-management-service` (Port: `8085`) — Control Plane
- **Tech**: Spring Boot, Spring Security, Spring Data JPA, Flyway, MySQL, Apache Kafka.
- **Responsibilities**:
  - Multi-tenant Organization onboarding and lifecycle management (`ACTIVE`, `SUSPENDED`, `INACTIVE`).
  - Route configuration management with path prefixes, allowed HTTP methods, and custom header injection/stripping rules.
  - Route snapshot versioning with point-in-time restore capabilities.
  - Append-only administrative audit logging (`control_plane_audit_records`).
  - Stateless authentication with JWT generation containing tenant metadata and role claims.

### 2. `smart-gateway-service` (Port: `8080`) — Data Plane
- **Tech**: Spring Boot, Spring Data Redis, Apache Kafka, RestClient.
- **Responsibilities**:
  - Unified dynamic routing matching incoming `/{orgSlug}/**` requests to target upstream URLs.
  - In-memory `RouteRegistry` compiling regex routes for ultra-fast lookup with thread-safe `AtomicReference` swapping.
  - Distributed sliding-window rate limiting via Redis Lua scripts.
  - Distributed idempotency checks caching upstream payloads in Redis with configurable TTLs.
  - Dynamic route synchronization via Kafka consumer (`ConfigurationEventConsumer`) and REST fallback (`ManagementApiClient`).

### 3. `analytics-service` (Port: `8082`) — Telemetry Sink
- **Tech**: Spring Boot, Spring Data MongoDB, Apache Kafka.
- **Responsibilities**:
  - Asynchronously consumes telemetry and traffic event streams from the Gateway.
  - Persists request metrics, response latencies, and error rates into MongoDB for time-series analytics and observability.

---

## 🛠️ Tech Stack & Prerequisites

- **Java Development Kit (JDK)**: Java 21+
- **Build Tool**: Apache Maven 3.9+
- **Containerization**: Docker & Docker Compose
- **Databases & Middleware**:
  - MySQL 8.0+
  - Redis 8.0+
  - Apache Kafka 4.4.0+ (KRaft mode)
  - MongoDB 7.0+

---

## 🚀 Getting Started

### 1. Clone the Repository
```bash
git clone https://github.com/omshewalegit/gatekeeper.git
cd gatekeeper
```

### 2. Start Infrastructure via Docker Compose
A unified `compose.yaml` is provided at the root to spin up Redis and Apache Kafka:
```bash
docker compose up -d
```
Verify running containers:
```bash
docker compose ps
```

### 3. Run the Microservices

#### Start API Management Service (Control Plane):
```bash
cd api-management-service
mvn spring-boot:run
```
*Runs on `http://localhost:8085`. Flyway will automatically execute database migrations.*

#### Start Smart Gateway Service (Data Plane):
```bash
cd ../smart-gateway-service
mvn spring-boot:run
```
*Runs on `http://localhost:8080`.*

#### Start Analytics Service:
```bash
cd ../analytics-service
mvn spring-boot:run
```
*Runs on `http://localhost:8082`.*

---

## 🔐 Core API Endpoints

### Authentication & Management (`api-management-service`)
| Method | Endpoint | Access | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | Public | Authenticates credentials and returns a signed JWT. |
| `GET` | `/api/health` | Public | Control plane health status check. |
| `POST` | `/api/platform/organizations` | `PLATFORM_ADMIN` | Registers a new tenant organization. |
| `GET` | `/api/platform/organizations` | `PLATFORM_ADMIN` | Retrieves all registered tenant organizations. |
| `PUT` | `/api/platform/organizations/{id}/status` | `PLATFORM_ADMIN` | Updates tenant status (`ACTIVE`, `SUSPENDED`). |
| `GET` | `/api/tenant/organization` | `TENANT_ADMIN` | Fetches tenant profile details for logged-in admin. |
| `POST` | `/api/tenant/routes` | `TENANT_ADMIN` | Creates an API route with custom policies & header rules. |
| `GET` | `/api/tenant/routes` | `TENANT_ADMIN` | Lists all configured API routes for the tenant. |
| `PUT` | `/api/tenant/routes/{id}` | `TENANT_ADMIN` | Updates route configuration and increments version. |
| `DELETE`| `/api/tenant/routes/{id}` | `TENANT_ADMIN` | Removes a route and triggers event-driven sync. |
| `GET` | `/api/tenant/routes/{id}/versions` | `TENANT_ADMIN` | Returns route historical version changelog. |

### Smart Gateway Ingress (`smart-gateway-service`)
| Method | Endpoint | Description |
|---|---|---|
| `ANY` | `/{organizationSlug}/**` | Dynamic entry point: matches route, enforces rate limit, checks idempotency, and forwards to upstream. |
| `GET` | `/api/health` | Gateway health status check. |

---

## 🧪 Quick Test: Rate Limiting & Dynamic Routing

1. **Log in as Platform Admin**:
```bash
curl -X POST http://localhost:8085/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "admin123"}'
```

2. **Send Traffic Through the Gateway**:
```bash
curl -X GET http://localhost:8080/techcorp/api/products \
  -H "X-Client-Id: client-123"
```

3. **Verify Idempotent POST**:
```bash
curl -X POST http://localhost:8080/techcorp/api/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 8a6f3b0e-2d91-4c12-9c3f-4e5a6b7c8d9e" \
  -d '{"item": "Laptop", "quantity": 1}'
```

---

## 📄 License
This project is licensed under the MIT License - see the LICENSE file for details.

