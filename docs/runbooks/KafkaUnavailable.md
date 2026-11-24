# Runbook: KafkaUnavailable (`KafkaUnavailable`, `KafkaConsumerLagHigh`, `KafkaExporterDown`)

## 1. What the Alert Means
- **`KafkaUnavailable` (`critical`)**: `kafka-exporter` reports fewer than 1 active Kafka broker (`kafka_brokers < 1`) for more than 1 minute. (Note: `KafkaUnavailable` automatically inhibits downstream `workflow`, `task`, and `slo` alerts in Alertmanager to prevent alert storms.)
- **`KafkaConsumerLagHigh` (`warning`)**: A consumer group's lag on a topic (`orynx:kafka:consumer_lag:by_group_topic`) exceeds 100 messages for over 3 minutes.
- **`KafkaExporterDown` (`warning`)**: Prometheus cannot scrape `kafka-exporter:9308` for over 1 minute.

## 2. User / Business Impact
OrynX relies on Kafka event choreography across `workflow-execution`, `task-started`, `task-completed`, `workflow-completed`, `workflow-paused`, `workflow-resumed`, and `workflow-cancelled`. When Kafka is unavailable, new workflows cannot be dispatched to `execution-service` and task completions cannot be reported back to `orchestrator-service`.

## 3. Initial Investigation Steps
1. Check the status of the `kafka` and `kafka-exporter` containers via `docker compose --profile observability ps`.
2. Inspect `kafka` container logs for KRaft controller errors, disk space exhaustion (`No space left on device`), or port binding conflicts.
3. For `KafkaConsumerLagHigh`, identify the lagging `consumergroup` and `topic` labels from the alert annotations.

## 4. Useful Commands
```bash
# Check Kafka and Kafka Exporter container status
docker compose --profile observability ps kafka kafka-exporter

# Inspect Kafka broker logs
docker compose --profile observability logs --tail=150 kafka

# List topics and check broker health inside the Kafka container
docker compose --profile observability exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Describe consumer group lag directly via Kafka CLI
docker compose --profile observability exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --all-groups --describe
```

## 5. Relevant Grafana Dashboards
- **OrynX Infrastructure & Messaging** (`uid: orynx-infrastructure`) — *Kafka Active Brokers*, *Kafka Topic Partitions*, and *Kafka Consumer Group Lag by Topic*.
- **OrynX SLO & Operational Reliability** (`uid: orynx-slo-reliability`) — *Max Kafka Consumer Group Lag* and *Infrastructure & Backing Store Health*.

## 6. Relevant Prometheus Queries
```promql
# Active Kafka broker count
kafka_brokers

# Exporter scrape health
up{job="kafka-exporter"}

# Consumer lag by consumer group and topic
orynx:kafka:consumer_lag:by_group_topic

# Maximum lag across all consumer groups
orynx:kafka:consumer_lag:max
```

## 7. Likely Root Causes
- **Broker Crash / OOM**: `kafka` container ran out of heap or page cache memory or crashed during log compaction.
- **Volume Corruption or Disk Full**: `orynx_kafka_data` volume ran out of disk space or suffered an unclean shutdown.
- **Consumer Service Down or Slow**: `orchestrator-service` or `execution-service` is down, paused in GC, or blocked on slow DB calls, causing `KafkaConsumerLagHigh`.

## 8. Safe Recovery Actions
1. If `kafka` is stopped or unhealthy, restart the broker and its exporter:
   ```bash
   docker compose --profile observability up -d kafka kafka-exporter
   ```
2. Verify that `orchestrator-service` and `execution-service` reconnect automatically (Spring Kafka retries broker connections automatically). If consumers remain disconnected after broker recovery, restart them:
   ```bash
   docker compose --profile observability restart orchestrator-service execution-service
   ```
3. If `KafkaExporterDown` fires while `kafka` is healthy, restart `kafka-exporter`:
   ```bash
   docker compose --profile observability restart kafka-exporter
   ```

## 9. Escalation Criteria
- Escalate to the **Platform Infrastructure Lead** if `kafka` fails to start due to KRaft metadata log corruption or persistent disk space exhaustion.

## 10. Verification After Recovery
1. Verify `kafka_brokers == 1` and `up{job="kafka-exporter"} == 1` in Prometheus.
2. Verify `orynx:kafka:consumer_lag:max` decreases below `100` as consumers drain queued events.
3. Confirm `KafkaUnavailable` / `KafkaConsumerLagHigh` resolves in Alertmanager.
