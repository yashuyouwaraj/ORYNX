# Runbook: Kafka Dead Letter Topic (DLT) Investigation & Safe Replay

## Symptoms
- Consumer error logs in `orchestrator-service`, `execution-service`, or `auth-service` after exhausting `FixedBackOff` retries.
- Messages accumulating on `*.DLT` topics (e.g., `workflow-execution.DLT`, `workflow-completed.DLT`, `workflow-failed.DLT`).
- Loki logs show `errorCategory="DESERIALIZATION"` or `errorCategory="INFRASTRUCTURE"`.

---

## 1. Triage & Inspection

### Identify DLT Backlog
Check **OrynX Kafka Messaging** dashboard (`kafka_consumergroup_lag`, `kafka_topic_partition_current_offset`) or inspect topics inside the Kafka container:
```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -i DLT
```

### Correlate Failed Messages in Loki & Tempo
1. Query Loki by `errorCategory` or `eventId`:
   ```logql
   {service_name=~"orchestrator-service|execution-service"} | errorCategory!=""
   ```
2. Extract `correlationId`, `workflowId`, `executionId`, `eventId`, and `trace_id` from structured metadata.
3. Click **View Trace in Tempo** on `trace_id` to inspect the failing consumer span and upstream producer span.

---

## 2. Root-Cause Classification
- **`DESERIALIZATION`**: Schema mismatch or corrupted JSON payload. Do **not** replay until the producer/consumer contract is fixed.
- **`VALIDATION` / `STATE_CONFLICT`**: Workflow already transitioned to a terminal state (`COMPLETED`/`CANCELLED`). Safe to archive without replay; idempotency guards prevent double-processing.
- **`INFRASTRUCTURE` / `TIMEOUT`**: Transient PostgreSQL, Redis, or downstream outage during processing. Candidate for controlled replay after the backing store is healthy.

---

## 3. Safe Recovery & Manual Intervention
1. Verify backing stores (`postgres`, `redis`, `kafka`) are healthy before replaying.
2. Prefer domain-level recovery first:
   - Query `GET /api/v1/workflows/{id}` and `GET /api/v1/workflows/{id}/executions`.
   - If the workflow is in `FAILED` state, trigger `PATCH /api/v1/workflows/{id}/retry` via the API Gateway so admission control, generation fencing, and fresh `eventId` correlation are enforced.
3. **Destructive Action Precaution**: Do **not** purge or delete Kafka topics (`kafka-topics.sh --delete`) or reset consumer offsets to `latest` without exporting DLT records for audit first.
