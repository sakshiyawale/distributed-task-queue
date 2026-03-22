# Distributed Task Queue System (Mini Celery)

A distributed task queue system built with Java Spring Boot, Apache Kafka, Redis, and React.js. Provides asynchronous task processing, real-time monitoring, priority queues, exponential backoff retry, CANCELLED status, paginated task listing, and JWT-secured REST + WebSocket APIs.

## Architecture

```
┌──────────────────┐     ┌──────────────────────────────────────┐     ┌────────────────────┐
│  React Frontend  │────►│         Spring Boot Backend          │────►│   Apache Kafka     │
│  localhost:3000  │◄────│  JWT Filter → Controller → Service   │     │  Priority Topics   │
│  Material-UI v7  │     │  @Async ThreadPoolTaskExecutor        │     │  + task-retry      │
│  SockJS/STOMP    │◄────│  WebSocket (STOMP /ws)               │     └────────────────────┘
└──────────────────┘     └──────────────────────────────────────┘              │
                                         │                                      ▼
                                         ▼                             ┌────────────────────┐
                                  ┌─────────────┐                     │     TaskWorker     │
                                  │    Redis    │◄────────────────────│  Strategy Pattern  │
                                  │  :6379      │                     │  @Async Execution  │
                                  └─────────────┘                     └────────────────────┘
                                         │
                                         ▼
                                  ┌─────────────┐
                                  │  H2 (users) │
                                  └─────────────┘
```

## Features

- **Priority Queues**: URGENT / HIGH / NORMAL / LOW — separate Kafka topics, consumed round-robin
- **Strategy Pattern**: `TaskProcessor` interface — `EmailTaskProcessor`, `ImageTaskProcessor`, `DataExportTaskProcessor`, `ReportTaskProcessor`, `GenericTaskProcessor` (fallback). New types added by creating a `@Component` class — no `if/switch` changes needed.
- **Non-blocking Workers**: `@Async("taskExecutor")` offloads execution to a `ThreadPoolTaskExecutor` (5–20 threads, queue 100). Kafka consumer threads are never blocked.
- **Manual Kafka Ack**: `ack-mode=manual`, `enable-auto-commit=false`. Offset committed immediately after task is accepted for async execution.
- **Idempotency**: Worker re-fetches task state from Redis before executing. Skips if already `COMPLETED` or `CANCELLED` (handles Kafka redelivery after rebalance).
- **Exponential Backoff Retry**: `delaySeconds = 2^retryCount` (2s, 4s, 8s). Delay is enforced by `ScheduledExecutorService.schedule()` before re-enqueueing to `task-retry` topic.
- **CANCELLED Status**: Distinct from `FAILED` — user-initiated cancellation. Does not increment retryCount. Worker skips re-execution via idempotency check.
- **Paginated Task List**: `GET /api/tasks?page=0&size=20` uses Redis `LRANGE` offset/limit. Avoids loading all task IDs at once.
- **Non-blocking Redis SCAN**: `getActiveWorkers()` uses cursor-based `SCAN` instead of blocking `KEYS`.
- **Role-based Access**: `DELETE /api/tasks/{id}` requires `ROLE_ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`).
- **Secure JWT**: Secret injected via `JWT_SECRET` env var (min 32 chars). Never hardcoded.
- **Configurable CORS**: Origins set via `CORS_ORIGINS` env var — no code change needed for deployment.
- **Real-time Updates**: WebSocket (STOMP) pushes task state changes to all connected frontends instantly.

## Technology Stack

| Layer | Technology |
|---|---|
| Backend | Java 11, Spring Boot 2.7, Spring Security, Spring Kafka |
| Auth | JWT (JJWT 0.9.1), BCrypt |
| Message Queue | Apache Kafka (5 topics) |
| Cache / State | Redis (key-value + List) |
| User Storage | H2 (in-memory) |
| Frontend | React 19, TypeScript, Material-UI v7, Axios, SockJS/STOMP |
| Containers | Docker & Docker Compose |
| Build | Maven 3.6+ |

## Prerequisites

- Java 11+
- Maven 3.6+
- Docker & Docker Compose
- Node.js 18+ (for React frontend)

## Running the Project

### 1. Start Infrastructure

```bash
docker-compose up -d zookeeper kafka redis
```

### 2. Set Environment Variables (optional — defaults work for local dev)

```bash
export JWT_SECRET="dev-secret-change-me-in-production-min-32-chars"
```

### 3. Start the Backend

```bash
mvn clean package
mvn spring-boot:run
```

### 4. Start the Frontend

```bash
cd task-queue-frontend
npm install
npm start
```
### Docker (full stack)

```bash
docker-compose up --build

# Scale workers
docker-compose up --scale task-queue-app=3
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | `dev-secret-change-me-in-production-min-32-chars` | HS256 signing key — **change in production** |
| `JWT_EXPIRATION_MS` | `86400000` | Token lifetime in ms (24 hours) |
| `CORS_ORIGINS` | `http://localhost:3000` | Allowed CORS origin(s) |
| `TASK_WORKER_CORE_POOL_SIZE` | `5` | ThreadPoolTaskExecutor core threads |
| `TASK_WORKER_MAX_POOL_SIZE` | `20` | ThreadPoolTaskExecutor max threads |
| `TASK_WORKER_QUEUE_CAPACITY` | `100` | Task queue depth before thread creation |

## API Reference

### Authentication

```http
POST /auth/register
{"username": "alice", "email": "alice@example.com", "password": "secret"}

POST /auth/login
{"username": "alice", "password": "secret"}
# Response: {"token": "<JWT>", "username": "alice", "role": "USER"}
```

All other endpoints require `Authorization: Bearer <JWT>`.

### Task Management

```http
# Submit task
POST /api/tasks
{
  "type": "EMAIL_SEND",
  "payload": {"recipient": "user@example.com", "subject": "Hello"},
  "priority": "HIGH"
}

# List tasks (paginated)
GET /api/tasks?page=0&size=20

# Get single task
GET /api/tasks/{taskId}

# Get by status
GET /api/tasks/status/{status}
# Status values: PENDING, PROCESSING, COMPLETED, FAILED, RETRYING, PAUSED, CANCELLED

# Cancel task
POST /api/tasks/{taskId}/cancel

# Retry failed/cancelled task
POST /api/tasks/{taskId}/retry

# Delete task (ADMIN role required)
DELETE /api/tasks/{taskId}

# Statistics
GET /api/tasks/statistics

# Active workers
GET /api/workers
```

### Task Types

| Type | Required Payload Fields | Simulated Duration |
|---|---|---|
| `EMAIL_SEND` | `recipient`, `subject` | 2s |
| `IMAGE_PROCESS` | `imageUrl`, `operation` | 5s |
| `DATA_EXPORT` | `format`, `recordCount` | 3s |
| `REPORT_GENERATE` | `reportType`, `dateRange` | 8s |
| `GENERIC` | any | 1s |

### WebSocket

```
Connect:   ws://localhost:8080/ws  (SockJS fallback)
Subscribe: /topic/task-updates     (receives Task JSON on every state change)
```

## Project Structure

```
src/main/java/com/taskqueue/
├── Task.java                          # Task entity + TaskStatus enum (PENDING/PROCESSING/COMPLETED/FAILED/RETRYING/PAUSED/CANCELLED)
├── TaskQueueApplication.java          # @EnableGlobalMethodSecurity, taskExecutor @Bean
├── config/
│   ├── JwtAuthenticationFilter.java   # OncePerRequestFilter — validates JWT, sets SecurityContext
│   ├── SecurityConfig.java            # CORS (env var), JWT filter chain, /auth/** open
│   └── WebSocketConfig.java           # STOMP /ws endpoint, /topic broker
├── controller/
│   ├── TaskController.java            # REST endpoints, @PreAuthorize on DELETE
│   └── AuthController.java            # /auth/register, /auth/login
├── service/
│   ├── TaskService.java               # Business logic, Redis ops, Kafka publish, SCAN-based worker lookup
│   ├── JwtService.java                # generateToken / validateToken (@Value secret)
│   ├── UserService.java               # register / findBy*, constructor-injected PasswordEncoder
│   └── CustomUserDetailsService.java  # Loads UserDetails from H2
├── worker/
│   ├── TaskWorker.java                # @KafkaListener (manual ack), idempotency check, @Async dispatch
│   ├── TaskProcessor.java             # Strategy interface: getType() + process(payload)
│   └── processors/
│       ├── EmailTaskProcessor.java
│       ├── ImageTaskProcessor.java
│       ├── DataExportTaskProcessor.java
│       ├── ReportTaskProcessor.java
│       └── GenericTaskProcessor.java  # Fallback — GENERIC_TYPE constant
└── model/
    ├── User.java
    ├── UserRepository.java
    ├── Role.java
    └── LoginResponse.java
```

## Key Design Decisions

### Strategy Pattern for Processors
All task processors implement `TaskProcessor`. `TaskWorker` builds a `Map<String, TaskProcessor>` registry at startup from Spring-injected `List<TaskProcessor>`. Adding a new task type requires only a new `@Component` class — zero changes to `TaskWorker`.

### Non-blocking Async Execution
Kafka consumer thread calls `ack.acknowledge()` immediately, then delegates to `@Async("taskExecutor")`. The `ThreadPoolTaskExecutor` runs up to 20 concurrent tasks. `Thread.sleep()` inside processors never blocks Kafka polling.

### Idempotency
Before executing, the worker re-fetches the latest task state from Redis. If the status is `COMPLETED` or `CANCELLED`, it skips execution. This prevents double-processing when Kafka redelivers messages after a consumer rebalance.

### CANCELLED vs FAILED
`CANCELLED` = user-initiated (clean stop). `FAILED` = system error after exhausting retries. Statistics track them separately. The idempotency check skips both statuses, so a cancelled task won't be re-executed if Kafka redelivers the message.

### Exponential Backoff
`ScheduledExecutorService.schedule(kafkaSend, 2^retryCount, SECONDS)` — the delay is actually enforced before the message is published to `task-retry`. Retry attempts: 2s → 4s → 8s → FAILED.

## Architecture Animation

Open [`system-animation.html`](system-animation.html) in any browser for an interactive SVG walkthrough of all 6 scenarios:

1. **Submit Task** — frontend → JWT filter → controller → service → Redis + Kafka
2. **Worker Processing** — Kafka consumer, idempotency check, @Async, strategy dispatch, WebSocket push
3. **Retry on Failure** — exponential backoff, ScheduledExecutorService, task-retry topic
4. **Auth Flow** — login, BCrypt, JWT generation, localStorage, interceptor
5. **Cancel Task** — CANCELLED status, idempotency skip, WebSocket notification
6. **Real-time Update** — STOMP subscription, broker routing, state re-render

---

Build: `mvn compile` — 23 source files, 0 errors. Tests: `mvn test` — 3/3 pass.
