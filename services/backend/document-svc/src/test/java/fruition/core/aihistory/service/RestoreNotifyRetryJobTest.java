package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * notify_pending 재시도 잡. DB에 재시도 횟수 컬럼이 없어 {@code createdAt}으로부터 지난
 * 시간으로 상한을 둔다.
 */
@ExtendWith(MockitoExtension.class)
class RestoreNotifyRetryJobTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String RESTORE_ID = "op_restore";
    private static final String TARGET_ID = "op_a2";

    @Mock OperationLogRepository operationLogRepository;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock RestoreExecuteService restoreExecuteService;
    @Mock RestoreScopeResolver scopeResolver;
    @Mock RestoreTargetValidator validator;

    private RestoreNotifyRetryJob job;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        job = new RestoreNotifyRetryJob(operationLogRepository, lifecycle, restoreExecuteService,
                scopeResolver, validator, objectMapper);
    }

    @Test
    @DisplayName("최대 재시도 기간을 넘기면 통지를 다시 시도하지 않고 실패로 확정한다")
    void givesUpAfterMaxRetryWindow() {
        OperationLog restore = restoreNotifyPending(Instant.now().minusSeconds(25 * 3600), "{}");
        when(operationLogRepository.findByStatus(eq(OperationStatus.notify_pending), any()))
                .thenReturn(List.of(restore));

        job.retry();

        verify(lifecycle).fail(eq(RESTORE_ID), anyString(), any());
        verify(restoreExecuteService, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재시도 기간 안이면 다시 통지하고 성공하면 rebuilding으로 넘긴다")
    void resendsAndFinishesWhenNotifySucceeds() throws Exception {
        RestorePlan plan = new RestorePlan(
                List.of(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1)));
        OperationLog restore = restoreNotifyPending(Instant.now(), objectMapper.writeValueAsString(plan));
        OperationLog target = OperationLog.completed(
                TARGET_ID, WORKSPACE, USER, OperationType.ingest, "doc_A", "요약", 1, Instant.now());

        when(operationLogRepository.findByStatus(eq(OperationStatus.notify_pending), any()))
                .thenReturn(List.of(restore));
        when(operationLogRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(scopeResolver.resolve(target)).thenReturn(Set.of(TARGET_ID));
        PageRestorePlan sourcePage = plan.pages().get(0);
        when(validator.requireApplicable(eq(target), eq(plan))).thenReturn(sourcePage);
        when(restoreExecuteService.notify(eq(restore), eq(target), eq(Set.of(TARGET_ID)),
                eq(plan), eq(sourcePage))).thenReturn(true);

        job.retry();

        verify(lifecycle).finish(eq(RESTORE_ID), eq(plan), eq(true), any());
        verify(lifecycle, never()).fail(any(), any(), any());
    }

    @Test
    @DisplayName("lint 되돌리기는 취소 집합과 source page 없이 재통지한다")
    void resendsLintWithoutExcludedOrSourcePage() throws Exception {
        RestorePlan plan = new RestorePlan(List.of(PageRestorePlan.delete("wp_C4")));
        OperationLog restore = restoreNotifyPending(Instant.now(), objectMapper.writeValueAsString(plan));
        OperationLog target = OperationLog.processing(
                TARGET_ID, WORKSPACE, USER, OperationType.lint, null, Instant.now());

        when(operationLogRepository.findByStatus(eq(OperationStatus.notify_pending), any()))
                .thenReturn(List.of(restore));
        when(operationLogRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(restoreExecuteService.notify(any(), any(), any(), any(), any())).thenReturn(true);

        job.retry();

        ArgumentCaptor<Set<String>> excludedCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<PageRestorePlan> sourcePageCaptor = ArgumentCaptor.forClass(PageRestorePlan.class);
        verify(restoreExecuteService).notify(eq(restore), eq(target),
                excludedCaptor.capture(), eq(plan), sourcePageCaptor.capture());
        assertThat(excludedCaptor.getValue()).isEmpty();
        assertThat(sourcePageCaptor.getValue()).isNull();
        verify(scopeResolver, never()).resolve(any());
        verify(validator, never()).requireApplicable(any(), any());
    }

    @Test
    @DisplayName("통지가 계속 실패해도 재시도 기간 안이면 notify_pending 그대로 둔다")
    void leavesNotifyPendingWhenStillFailing() throws Exception {
        RestorePlan plan = new RestorePlan(
                List.of(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1)));
        OperationLog restore = restoreNotifyPending(Instant.now(), objectMapper.writeValueAsString(plan));
        OperationLog target = OperationLog.completed(
                TARGET_ID, WORKSPACE, USER, OperationType.ingest, "doc_A", "요약", 1, Instant.now());

        when(operationLogRepository.findByStatus(eq(OperationStatus.notify_pending), any()))
                .thenReturn(List.of(restore));
        when(operationLogRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(scopeResolver.resolve(target)).thenReturn(Set.of(TARGET_ID));
        when(validator.requireApplicable(any(), any())).thenReturn(plan.pages().get(0));
        when(restoreExecuteService.notify(any(), any(), any(), any(), any())).thenReturn(false);

        job.retry();

        verify(lifecycle, never()).finish(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(lifecycle, never()).fail(any(), any(), any());
    }

    private OperationLog restoreNotifyPending(Instant createdAt, String manifestJson) {
        OperationLog restore = OperationLog.applying(
                RESTORE_ID, WORKSPACE, USER, "doc_A", TARGET_ID, manifestJson, createdAt);
        restore.moveTo(OperationStatus.notify_pending);
        return restore;
    }
}
