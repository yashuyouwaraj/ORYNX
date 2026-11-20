package com.orynx.execution.coordination;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL Distributed Execution Coordination Integration Tests (Phase A, Step 15.1 Review).
 * Validates distributed row locking, fencing tokens/generations, lease heartbeat ownership checks,
 * stale worker rejection, and checkpoint storage on PostgreSQL 17 at localhost:5439.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ExecutionCoordinationPostgreSqlTest {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
    }

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void setUpAll() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5439/orynx_test?options=-c%20timezone=UTC");
        dataSource.setUsername("testuser");
        dataSource.setPassword("testpass");

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS workflow_execution_coordination CASCADE");

        jdbcTemplate.execute("""
            CREATE TABLE workflow_execution_coordination (
                workflow_id BIGINT PRIMARY KEY,
                workflow_name VARCHAR(255),
                status VARCHAR(32) NOT NULL,
                instance_id VARCHAR(128),
                last_completed_execution_order INTEGER,
                acquired_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP,
                lease_expires_at TIMESTAMP,
                generation BIGINT NOT NULL DEFAULT 1,
                recovery_attempts INTEGER NOT NULL DEFAULT 0,
                payload_json TEXT,
                version BIGINT DEFAULT 0
            )
        """);
    }

    @Test
    @Order(1)
    @DisplayName("Pessimistic locking prevents duplicate concurrent lease acquisition across two instances")
    void testConcurrentLeaseAcquisitionSerializesAcrossInstances() throws Exception {
        Long workflowId = 5001L;
        int threadCount = 2;
        ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        AtomicInteger acquiredCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = List.of(
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    boolean acquired = tryAcquireLeaseInDb(workflowId, "Concurrent Pipeline", "instance-A", 300);
                    if (acquired) acquiredCount.incrementAndGet();
                    else duplicateCount.incrementAndGet();
                    return null;
                },
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    boolean acquired = tryAcquireLeaseInDb(workflowId, "Concurrent Pipeline", "instance-B", 300);
                    if (acquired) acquiredCount.incrementAndGet();
                    else duplicateCount.incrementAndGet();
                    return null;
                }
        );

        List<Future<Void>> futures = threadPool.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        threadPool.shutdown();

        // Exactly one instance must acquire the lease; the second must be rejected as duplicate
        assertThat(acquiredCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(1);

        // Verify state in PostgreSQL
        var record = jdbcTemplate.queryForMap(
                "SELECT status, generation FROM workflow_execution_coordination WHERE workflow_id = ?",
                workflowId
        );
        assertThat(record.get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) record.get("generation")).longValue()).isEqualTo(1L);
    }

    @Test
    @Order(2)
    @DisplayName("Expired lease from crashed instance is safely reclaimed by healthy instance with incremented generation")
    void testReclaimExpiredLeaseFromCrashedInstanceInPostgreSql() {
        Long workflowId = 5002L;
        Instant now = Instant.now();
        Instant expiredLease = now.minusSeconds(60); // Expired 60s ago

        // Seed crashed instance A record (generation = 1)
        jdbcTemplate.update("""
            INSERT INTO workflow_execution_coordination
            (workflow_id, workflow_name, status, instance_id, acquired_at, updated_at, lease_expires_at, generation, recovery_attempts, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, 0)
        """, workflowId, "Crash Recovery Pipeline", "RUNNING", "crashed-instance-A",
                Timestamp.from(now.minusSeconds(400)), Timestamp.from(now.minusSeconds(400)),
                Timestamp.from(expiredLease));

        // Instance B attempts acquisition and reclaims the expired lease
        boolean reclaimed = tryAcquireLeaseInDb(workflowId, "Crash Recovery Pipeline", "healthy-instance-B", 300);
        assertThat(reclaimed).isTrue();

        // Verify updated record in PostgreSQL: generation MUST be incremented to 2
        var record = jdbcTemplate.queryForMap(
                "SELECT status, instance_id, generation, lease_expires_at FROM workflow_execution_coordination WHERE workflow_id = ?",
                workflowId
        );
        assertThat(record.get("status")).isEqualTo("DISPATCHED");
        assertThat(record.get("instance_id")).isEqualTo("healthy-instance-B");
        assertThat(((Number) record.get("generation")).longValue()).isEqualTo(2L);
        Timestamp newLease = (Timestamp) record.get("lease_expires_at");
        assertThat(newLease.toInstant()).isAfter(now);
    }

    @Test
    @Order(3)
    @DisplayName("Heartbeat extends lease only when instanceId and generation match current owner")
    void testHeartbeatExtendsLeaseOnlyForMatchingOwnerAndGeneration() {
        Long workflowId = 5005L;
        Instant now = Instant.now();
        Instant initialExpiry = now.plusSeconds(30);

        // Seed RUNNING record owned by instance-A at generation 1
        jdbcTemplate.update("""
            INSERT INTO workflow_execution_coordination
            (workflow_id, workflow_name, status, instance_id, acquired_at, updated_at, lease_expires_at, generation, recovery_attempts, version)
            VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, 1, 0, 0)
        """, workflowId, "Heartbeat Pipeline", "instance-A",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(initialExpiry));

        // Valid owner heartbeat succeeds
        Instant extendedExpiry = now.plusSeconds(300);
        int rowsUpdated = jdbcTemplate.update("""
            UPDATE workflow_execution_coordination
            SET updated_at = ?, lease_expires_at = ?
            WHERE workflow_id = ? AND generation = ? AND instance_id = ? AND status = 'RUNNING'
        """, Timestamp.from(now), Timestamp.from(extendedExpiry), workflowId, 1L, "instance-A");
        assertThat(rowsUpdated).isEqualTo(1);

        // Stale owner or wrong generation heartbeat fails (0 rows)
        int staleAttempt = jdbcTemplate.update("""
            UPDATE workflow_execution_coordination
            SET updated_at = ?, lease_expires_at = ?
            WHERE workflow_id = ? AND generation = ? AND instance_id = ? AND status = 'RUNNING'
        """, Timestamp.from(now), Timestamp.from(extendedExpiry), workflowId, 2L, "instance-A");
        assertThat(staleAttempt).isEqualTo(0);

        int wrongInstanceAttempt = jdbcTemplate.update("""
            UPDATE workflow_execution_coordination
            SET updated_at = ?, lease_expires_at = ?
            WHERE workflow_id = ? AND generation = ? AND instance_id = ? AND status = 'RUNNING'
        """, Timestamp.from(now), Timestamp.from(extendedExpiry), workflowId, 1L, "imposter-instance");
        assertThat(wrongInstanceAttempt).isEqualTo(0);
    }

    @Test
    @Order(4)
    @DisplayName("Stale worker from old generation cannot overwrite status of new generation owner")
    void testStaleWorkerFencingInPostgreSql() {
        Long workflowId = 5006L;
        Instant now = Instant.now();

        // Seed record currently at generation 2 owned by instance-B
        jdbcTemplate.update("""
            INSERT INTO workflow_execution_coordination
            (workflow_id, workflow_name, status, instance_id, acquired_at, updated_at, lease_expires_at, generation, recovery_attempts, version)
            VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, 2, 1, 0)
        """, workflowId, "Fenced Pipeline", "instance-B",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(300)));

        // Stale worker (generation 1, instance-A) attempts to mark workflow COMPLETED
        int updated = jdbcTemplate.update("""
            UPDATE workflow_execution_coordination
            SET status = 'COMPLETED', updated_at = ?, lease_expires_at = NULL
            WHERE workflow_id = ? AND generation = ? AND instance_id = ?
        """, Timestamp.from(now), workflowId, 1L, "instance-A");

        // Stale worker MUST be fenced (0 rows updated)
        assertThat(updated).isEqualTo(0);

        // Current status must remain RUNNING at generation 2
        var record = jdbcTemplate.queryForMap(
                "SELECT status, generation, instance_id FROM workflow_execution_coordination WHERE workflow_id = ?",
                workflowId
        );
        assertThat(record.get("status")).isEqualTo("RUNNING");
        assertThat(((Number) record.get("generation")).longValue()).isEqualTo(2L);
        assertThat(record.get("instance_id")).isEqualTo("instance-B");
    }

    @Test
    @Order(5)
    @DisplayName("Terminal workflow state prevents duplicate execution in PostgreSQL")
    void testTerminalStateRejectionInPostgreSql() {
        Long workflowId = 5003L;
        Instant now = Instant.now();

        // Seed COMPLETED record
        jdbcTemplate.update("""
            INSERT INTO workflow_execution_coordination
            (workflow_id, workflow_name, status, instance_id, acquired_at, updated_at, lease_expires_at, generation, version)
            VALUES (?, ?, ?, ?, ?, ?, NULL, 1, 0)
        """, workflowId, "Completed Pipeline", "COMPLETED", "instance-A",
                Timestamp.from(now.minusSeconds(200)), Timestamp.from(now.minusSeconds(100)));

        // Attempt lease acquisition
        boolean acquired = tryAcquireLeaseInDb(workflowId, "Completed Pipeline", "instance-B", 300);
        assertThat(acquired).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("Paused workflow preserves checkpoint and rejects duplicate dispatch in PostgreSQL")
    void testPausedWorkflowPreservesCheckpointInPostgreSql() {
        Long workflowId = 5004L;
        Instant now = Instant.now();

        // Seed PAUSED record with checkpoint 3
        jdbcTemplate.update("""
            INSERT INTO workflow_execution_coordination
            (workflow_id, workflow_name, status, instance_id, last_completed_execution_order, acquired_at, updated_at, lease_expires_at, generation, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 1, 0)
        """, workflowId, "Paused Pipeline", "PAUSED", "instance-A", 3,
                Timestamp.from(now.minusSeconds(200)), Timestamp.from(now.minusSeconds(50)));

        // Attempt lease acquisition without resume
        boolean acquired = tryAcquireLeaseInDb(workflowId, "Paused Pipeline", "instance-B", 300);
        assertThat(acquired).isFalse();

        // Checkpoint must be intact
        Integer checkpoint = jdbcTemplate.queryForObject(
                "SELECT last_completed_execution_order FROM workflow_execution_coordination WHERE workflow_id = ?",
                Integer.class,
                workflowId
        );
        assertThat(checkpoint).isEqualTo(3);
    }

    private boolean tryAcquireLeaseInDb(Long workflowId, String name, String instanceId, int leaseDurationSec) {
        try {
            Boolean result = transactionTemplate.execute(status -> {
                // Pessimistic write lock on existing row
                List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT status, instance_id, lease_expires_at, generation FROM workflow_execution_coordination WHERE workflow_id = ? FOR UPDATE",
                        workflowId
                );

                Instant now = Instant.now();
                Instant newLeaseExpiry = now.plusSeconds(leaseDurationSec);

                if (rows.isEmpty()) {
                    jdbcTemplate.update("""
                        INSERT INTO workflow_execution_coordination
                        (workflow_id, workflow_name, status, instance_id, acquired_at, updated_at, lease_expires_at, generation, recovery_attempts, version)
                        VALUES (?, ?, 'DISPATCHED', ?, ?, ?, ?, 1, 0, 0)
                    """, workflowId, name, instanceId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(newLeaseExpiry));
                    return true;
                }

                var row = rows.get(0);
                String currentStatus = (String) row.get("status");
                Timestamp leaseExpiresAt = (Timestamp) row.get("lease_expires_at");
                long currentGen = ((Number) row.get("generation")).longValue();

                if ("COMPLETED".equals(currentStatus) || "CANCELLED".equals(currentStatus) || "PAUSED".equals(currentStatus)) {
                    return false;
                }

                if (leaseExpiresAt != null && now.isBefore(leaseExpiresAt.toInstant())) {
                    return false; // Active lease
                }

                // Expired lease reclamation with incremented generation
                jdbcTemplate.update("""
                    UPDATE workflow_execution_coordination
                    SET status = 'DISPATCHED', instance_id = ?, updated_at = ?, lease_expires_at = ?, generation = ?, version = version + 1
                    WHERE workflow_id = ?
                """, instanceId, Timestamp.from(now), Timestamp.from(newLeaseExpiry), currentGen + 1L, workflowId);
                return true;
            });

            return Boolean.TRUE.equals(result);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert race caught by primary key constraint
            return false;
        }
    }
}
