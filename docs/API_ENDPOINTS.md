# OrynX API Endpoints

Postman and client reference for the OrynX platform. All external and frontend API traffic must route through the **API Gateway** on port `8080`.

## Network & Service Topology

| Component | Container Port | Inter-Service Route Property | Host Publication Boundary |
| :--- | :---: | :--- | :--- |
| API Gateway (`gateway-service`) | `8080` | Public Ingress | Published ingress (`8080:8080`) |
| Auth service (`auth-service`) | `8081` | `AUTH_SERVICE_URL` (default `http://localhost:8081`) | Internal Docker network (`expose: 8081`) or `127.0.0.1:8081:8081` for local debug |
| Orchestrator service (`orchestrator-service`) | `8082` | `ORCHESTRATOR_SERVICE_URL` (default `http://localhost:8082`) | Internal Docker network or `127.0.0.1:8082:8082` for local debug |
| Planning service (`planning-service`) | `8083` | `PLANNING_SERVICE_URL` (default `http://localhost:8083`) | Internal Docker network or `127.0.0.1:8083:8083` for local debug |
| Execution service (`execution-service`) | `8084` | Kafka internal worker (no HTTP ingress) | Internal Docker network or `127.0.0.1:8084:8084` for local debug |

> **Security Note:** Backend services (`8081`–`8084`) listen on their default container interface so container-to-container calls (e.g. `http://auth-service:8081`, `http://orchestrator-service:8082`, `http://planning-service:8083`) work across Docker bridge networks. Host exposure is controlled at the Docker port-publishing boundary: internal backend services and backing stores must use `expose` or bind host ports exclusively to `127.0.0.1` (never `0.0.0.0:<port>`). Spring Boot Actuator exposes only `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` (`GET`/`HEAD`) with health details suppressed.

## Gateway Endpoints (`http://localhost:8080`)

### Authentication & User Management

```text
GET  http://localhost:8080/api/v1/auth/health
POST http://localhost:8080/api/v1/users/register
POST http://localhost:8080/api/v1/users/login
GET  http://localhost:8080/api/v1/users/profile
GET  http://localhost:8080/api/v1/users/admin
```

Register and login request body:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

### Workflows

```text
POST  http://localhost:8080/api/v1/workflows
GET   http://localhost:8080/api/v1/workflows
GET   http://localhost:8080/api/v1/workflows/{id}
GET   http://localhost:8080/api/v1/workflows/status/{status}
GET   http://localhost:8080/api/v1/workflows/{id}/tasks
GET   http://localhost:8080/api/v1/workflows/dashboard/summary
GET   http://localhost:8080/api/v1/workflows/{id}/executions
GET   http://localhost:8080/api/v1/workflows/{id}/analytics
PATCH http://localhost:8080/api/v1/workflows/{id}/start
PATCH http://localhost:8080/api/v1/workflows/{id}/cancel
PATCH http://localhost:8080/api/v1/workflows/{id}/pause
PATCH http://localhost:8080/api/v1/workflows/{id}/resume
PATCH http://localhost:8080/api/v1/workflows/{id}/retry
```

Create workflow request body:

```json
{
  "name": "Example workflow",
  "goal": "Process the customer request",
  "scheduledAt": null,
  "dependsOnWorkflowId": null
}
```

`GET /api/v1/workflows` supports Spring pagination parameters such as `?page=0&size=20&sort=id,desc`.

### AI Workflow Endpoints

```text
POST http://localhost:8080/api/v1/ai/plan
POST http://localhost:8080/api/v1/ai/workflows
```

Request body:

```json
{
  "name": "AI workflow",
  "goal": "Process incoming support tickets",
  "scheduledAt": null
}
```

### Planning Endpoint

```text
POST http://localhost:8080/api/v1/plans
```

Request body:

```json
{
  "goal": "Process incoming support tickets"
}
```

### Monitoring, Analytics & Timeline

```text
GET http://localhost:8080/api/v1/platform/health
GET http://localhost:8080/api/v1/analytics
GET http://localhost:8080/api/v1/performance
GET http://localhost:8080/api/v1/performance/summary
GET http://localhost:8080/api/v1/performance/health
GET http://localhost:8080/api/v1/timeline/{workflowId}
GET http://localhost:8080/actuator/health
```

## Headers

For JSON requests:

```text
Content-Type: application/json
```

For protected endpoints, use the JWT returned by login:

```text
Authorization: Bearer YOUR_JWT_TOKEN
```
