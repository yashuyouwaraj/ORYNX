# OrynX API Endpoints

Postman reference for the locally running OrynX services.

## Local service URLs

| Component | Base URL |
|---|---|
| API Gateway | `http://localhost:8080` |
| Auth service | `http://localhost:8081` |
| Orchestrator service | `http://localhost:8082` |
| Planning service | `http://localhost:8083` |

## Gateway endpoints

These endpoints are routed through the API Gateway on port `8080`.

### Authentication health

```text
GET http://localhost:8080/api/v1/auth/health
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

### AI workflow endpoints

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

### Planning endpoint

```text
POST http://localhost:8080/api/v1/plans
```

Request body:

```json
{
  "goal": "Process incoming support tickets"
}
```

### Platform health

```text
GET http://localhost:8080/api/v1/platform/health
```

## Direct service endpoints

These endpoints exist in the application but are not currently routed by the gateway configuration.

### Auth service: port 8081

```text
POST http://localhost:8081/api/v1/users/register
POST http://localhost:8081/api/v1/users/login
GET  http://localhost:8081/api/v1/users/profile
GET  http://localhost:8081/api/v1/users/admin
```

Register and login request body:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

### Orchestrator service: port 8082

```text
GET http://localhost:8082/api/v1/analytics
GET http://localhost:8082/api/v1/performance
GET http://localhost:8082/api/v1/performance/summary
GET http://localhost:8082/api/v1/performance/health
GET http://localhost:8082/api/v1/timeline/{workflowId}
```

## Postman headers

For JSON requests:

```text
Content-Type: application/json
```

For protected endpoints, use the JWT returned by login:

```text
Authorization: Bearer YOUR_JWT_TOKEN
```

## Endpoint coverage

This file includes all 26 REST endpoints currently declared by the backend controllers:

- 5 auth endpoints, including auth health
- 12 workflow endpoints
- 2 AI workflow endpoints
- 1 planning endpoint
- 1 platform health endpoint
- 1 analytics endpoint
- 3 performance endpoints
- 1 execution timeline endpoint
