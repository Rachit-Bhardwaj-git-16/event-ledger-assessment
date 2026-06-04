# Event Ledger

A distributed event ledger system built with Spring Boot, composed of two independent microservices that process financial transaction events with full observability, resiliency, and distributed tracing.

---


## Architecture Overview

```
Browser / Client
      │
      ▼  REST (port 8080)
┌─────────────────────┐
│   Event Gateway     │  ← Public-facing. Receives events, enforces idempotency,
│   (event-gateway)   │    stores events in its own H2 DB, calls Account Service.
└──────────┬──────────┘
           │ REST (port 8081) via OpenFeign
           │ Header: X-Trace-Id (trace propagation)
           ▼
┌─────────────────────┐
│   Account Service   │  ← Internal only. Manages account state and balances.
│   (account-service) │    Has its own H2 DB — no shared state with gateway.
└─────────────────────┘
```

### Event Gateway (`event-gateway`)
The entry point for all external clients. Responsibilities:
- Accept and validate incoming transaction events
- Enforce idempotency (same `eventId` → return original event)
- Persist events in its own H2 database
- Forward transactions to Account Service via OpenFeign (with circuit breaker + retry)
- Generate and propagate `X-Trace-Id` across the request lifecycle

### Account Service (`account-service`)
Internal service, not exposed publicly. Responsibilities:
- Apply CREDIT / DEBIT transactions to accounts
- Maintain running balance, recalculated from all transactions (handles out-of-order events correctly)
- Expose balance and account detail queries

### Resiliency Pattern — Circuit Breaker + Retry
The Gateway wraps all Account Service calls with **Resilience4j Circuit Breaker** and **Retry with exponential backoff**:

- **Circuit Breaker**: After 5+ failures with ≥50% failure rate, the circuit opens and immediately returns `503 Service Unavailable` to clients. After 5 seconds, it transitions to half-open to probe recovery.
- **Retry**: Up to 3 attempts with exponential backoff (1s, 2s) before circuit breaker registers a failure.
- **Timeout**: 5-second timeout per call — no hanging requests.

This was chosen over a pure bulkhead because the Account Service is synchronous and business-critical for POST operations. When it's down, clients get a fast, clear 503 rather than a hanging thread. GET operations (read-only from gateway DB) continue to work normally during outages.

---

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for containerised run)

---

## Running with Docker Compose (Recommended)

> **Note:** Build the JARs first, then start Docker Compose.

```bash
# 1. Build both services
mvn clean package -DskipTests

# 2. Start everything (Gateway + Account Service + Jaeger + Prometheus)
docker-compose up --build

# Services will be available at:
#   Event Gateway:    http://localhost:8080
#   Account Service:  http://localhost:8081
#   Jaeger UI:        http://localhost:16686
#   Prometheus:       http://localhost:9090
```

---

## Running Locally (Without Docker)

Open two terminals:

**Terminal 1 — Account Service (start first)**
```bash
cd account-service
mvn spring-boot:run
# Starts on http://localhost:8081
```

**Terminal 2 — Event Gateway**
```bash
cd event-gateway
mvn spring-boot:run
# Starts on http://localhost:8080
```

---

## Running the Tests

```bash
# Run all tests (both services)
mvn test

# Run only event-gateway tests (includes integration tests)
cd event-gateway
mvn test

# Run only account-service tests
cd account-service
mvn test
```

Test coverage includes:
- ✅ Idempotency (unit + integration)
- ✅ Out-of-order event handling and balance correctness
- ✅ Input validation (missing fields, zero/negative amounts, invalid currency)
- ✅ Resiliency — circuit breaker opens and returns 503 when account service is down
- ✅ Graceful degradation — GET endpoints work without account service
- ✅ Distributed trace propagation — `X-Trace-Id` flows from gateway to account service
- ✅ Full Gateway → Account Service integration flow (via WireMock)

---

## API Reference

### Event Gateway (`:8080`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET`  | `/events/{id}` | Get event by internal ID |
| `GET`  | `/events?account={accountId}` | List events for an account (chronological order) |
| `GET`  | `/health` | Health check |
| `GET`  | `/actuator/health` | Detailed health (includes DB + circuit breaker state) |
| `GET`  | `/actuator/prometheus` | Prometheus metrics |
| `GET`  | `/swagger-ui.html` | Swagger UI |

### Account Service (`:8081`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction |
| `GET`  | `/accounts/{accountId}/balance` | Get current balance |
| `GET`  | `/accounts/{accountId}` | Get account details |
| `GET`  | `/health` | Health check |

### Sample Request

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
  }'
```

---

## Test Execution

All automated tests were executed successfully.

BUILD SUCCESS
Failures: 0
Errors: 0

<img width="1920" height="1023" alt="image" src="https://github.com/user-attachments/assets/46cab937-d22f-4510-b758-231465aa35eb" />


## Observability

- **Structured JSON logging**: Both services emit JSON logs (via `logstash-logback-encoder`) with `traceId`, `timestamp`, `level`, and `service` fields.
- **Distributed tracing**: `X-Trace-Id` header generated at the gateway, propagated to account service, logged by both, and returned in the response header.
- **Custom metrics**: `events_created_total`, `events_failed_total` (gateway), `account_transactions_total` (account service) — exposed via `/actuator/prometheus`.
- **Jaeger**: Trace visualisation at `http://localhost:16686` when running via Docker Compose.

## Design Decisions
- Account Service called BEFORE saving to Gateway DB — prevents ghost events
- Balance recalculated from all transactions on every update — ensures out-of-order correctness
- Circuit breaker chosen over bulkhead — Account Service is synchronous and business-critical

- **Request/Response Logging**: Every incoming HTTP request and outgoing response is logged with method, URI, status code, duration (ms), traceId, and request body. Error responses (4xx/5xx) are logged at WARN level with response body for easier debugging. Internal paths (actuator, swagger, h2-console) are excluded from logging to reduce noise.


