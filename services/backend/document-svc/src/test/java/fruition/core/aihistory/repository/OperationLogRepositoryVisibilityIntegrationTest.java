package fruition.core.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OperationLogRepositoryVisibilityIntegrationTest {

    @Autowired OperationLogRepository operationLogRepository;

    @Test
    void findPage_hidesProcessingUnlessStatusIsExplicitlyRequested() {
        String workspaceId = "ws_" + UUID.randomUUID();
        Instant now = Instant.now();
        OperationLog changed = OperationLog.completed(
                "op_changed_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.document_edit, null, "문서 편집 완료", 1, now.minusSeconds(3));
        OperationLog unchanged = OperationLog.completed(
                "op_unchanged_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.document_edit, null, "변경 없음", 0, now.minusSeconds(2));
        OperationLog conflict = OperationLog.applyingDocumentEdit(
                "op_conflict_" + UUID.randomUUID(), workspaceId, "user_1", null, now.minusSeconds(1));
        conflict.complete(OperationStatus.conflict, "편집 충돌", 0, null, now.minusSeconds(1));
        OperationLog ingest = OperationLog.processing(
                "op_ingest_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.ingest, null, now);
        OperationLog rebuilding = OperationLog.processing(
                "op_rebuilding_" + UUID.randomUUID(), workspaceId, "user_1",
                OperationType.lint, null, now.minusSeconds(1));
        rebuilding.moveTo(OperationStatus.rebuilding);
        operationLogRepository.saveAll(List.of(changed, unchanged, conflict, ingest, rebuilding));

        List<OperationLog> visible = operationLogRepository.findPage(
                workspaceId, null, null, now.plusSeconds(1),
                OperationType.document_edit, OperationStatus.succeeded, activeStatuses(),
                PageRequest.of(0, 20));

        assertThat(visible)
                .extracting(OperationLog::getOperationId)
                .containsExactly(changed.getOperationId());

        List<OperationLog> processing = operationLogRepository.findPage(
                workspaceId, null, OperationStatus.processing, now.plusSeconds(1),
                OperationType.document_edit, OperationStatus.succeeded, activeStatuses(),
                PageRequest.of(0, 20));

        assertThat(processing)
                .extracting(OperationLog::getOperationId)
                .containsExactly(ingest.getOperationId());
    }

    private List<OperationStatus> activeStatuses() {
        return List.of(OperationStatus.processing, OperationStatus.applying,
                OperationStatus.notify_pending, OperationStatus.rebuilding);
    }
}
