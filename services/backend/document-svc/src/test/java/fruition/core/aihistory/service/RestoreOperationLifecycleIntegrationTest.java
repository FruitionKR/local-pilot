package fruition.core.aihistory.service;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 복구 선점이 애플리케이션 인스턴스 사이에서도 DB unique index로 직렬화되는지 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RestoreOperationLifecycleIntegrationTest {

    @Autowired RestoreOperationLifecycle lifecycle;
    @Autowired OperationLogRepository operationLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void samePreviewToken_concurrentlyClaimsOnlyOneRestore() throws Exception {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.ingest,
                null, "ingest", 1, Instant.now());
        operationLogRepository.save(target);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Optional<OperationLog>>) () -> {
                ready.countDown();
                start.await();
                return lifecycle.startQueued(target, "{}", "a".repeat(64), Instant.now());
            };
            Future<Optional<OperationLog>> first = executor.submit(task);
            Future<Optional<OperationLog>> second = executor.submit(task);
            ready.await();
            start.countDown();

            List<Optional<OperationLog>> results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ?",
                    Long.class, targetId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void documentEdit_samePreviewToken_concurrentlyClaimsOnlyOneRestore() throws Exception {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.document_edit,
                null, "edit", 1, Instant.now());
        operationLogRepository.save(target);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var task = (java.util.concurrent.Callable<Optional<OperationLog>>) () -> {
                ready.countDown();
                start.await();
                return lifecycle.start(target, "{}", "b".repeat(64), Instant.now());
            };
            Future<Optional<OperationLog>> first = executor.submit(task);
            Future<Optional<OperationLog>> second = executor.submit(task);
            ready.await();
            start.countDown();

            List<Optional<OperationLog>> results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ai_operation_logs WHERE restored_from = ?",
                    Long.class, targetId)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void documentEdit_failedClaimRejectsSameTokenRetry() {
        String targetId = "op_target_" + UUID.randomUUID();
        String workspaceId = "ws_" + UUID.randomUUID();
        OperationLog target = OperationLog.completed(
                targetId, workspaceId, "user_1", OperationType.document_edit,
                null, "edit", 1, Instant.now());
        operationLogRepository.save(target);

        Optional<OperationLog> first = lifecycle.start(target, "{}", "c".repeat(64), Instant.now());
        assertThat(first).isPresent();
        lifecycle.fail(first.orElseThrow().getOperationId(), "document restore failed", Instant.now());

        assertThat(lifecycle.start(target, "{}", "c".repeat(64), Instant.now())).isEmpty();
        assertThat(operationLogRepository.findById(first.orElseThrow().getOperationId()))
                .get().extracting(OperationLog::getStatus).isEqualTo(fruition.core.aihistory.domain.OperationStatus.failed);
    }

    @Test
    void migration_addsRestoreTokenClaimIndex() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'ai_operation_logs'
                  AND column_name = 'restore_token_hash'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE tablename = 'ai_operation_logs'
                  AND indexname = 'uk_ai_operation_logs_restore_token'
                """, Integer.class)).isEqualTo(1);
    }
}
