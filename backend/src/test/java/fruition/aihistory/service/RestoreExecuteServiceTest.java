package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestoreExecuteResponse;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.exception.RestorePreviewStaleException;
import fruition.aihistory.repository.PipelineRestoreRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 복구 실행. 미리보기 시점과 달라졌으면 막고, 반영 이후에는 통지 성패에 따라 상태만 갈린다.
 */
@ExtendWith(MockitoExtension.class)
class RestoreExecuteServiceTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String TARGET = "op_a2";
    private static final String TOKEN = "signed-token";
    private static final Instant T = Instant.parse("2026-07-28T10:00:00Z");

    @Mock RestorePreviewService previewService;
    @Mock RestoreScopeResolver scopeResolver;
    @Mock RestorePlanner planner;
    @Mock PreviewTokenSigner tokenSigner;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock RestoreApplier applier;
    @Mock PipelineRestoreRequester restoreRequester;

    private RestoreExecuteService service;

    @BeforeEach
    void setUp() {
        service = new RestoreExecuteService(previewService, scopeResolver, planner, tokenSigner,
                lifecycle, applier, restoreRequester, new ObjectMapper(), "http://backend:8080");
    }

    @Test
    @DisplayName("미리보기 이후 대상이 바뀌면 반영하지 않고 409를 낸다")
    void rejectsStalePreviewToken() {
        givenTarget(OperationType.ingest);
        when(scopeResolver.resolve(any())).thenReturn(Set.of("op_a3"));
        when(previewService.loadContributions(any())).thenReturn(Map.of());
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(false);

        assertThatThrownBy(() -> execute())
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(applier, never()).apply(any(), any(), any(), any());
        verify(lifecycle, never()).start(any(), anyString(), any());
    }

    @Test
    @DisplayName("ingest·lint가 아닌 작업은 되돌리지 않는다")
    void rejectsNonRestorableOperationType() {
        givenTarget(OperationType.document_edit);

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        verify(applier, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("되돌릴 Wiki 페이지가 없으면 작업을 만들지 않는다")
    void rejectsEmptyPlan() {
        givenValidPreview();
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of()));

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        verify(lifecycle, never()).start(any(), anyString(), any());
    }

    @Test
    @DisplayName("재작성 대상이 없고 통지에 성공하면 그 자리에서 끝난다")
    void completesWhenNothingToRebuild() {
        givenValidPreview();
        givenPlan(PageRestorePlan.delete("page_1"), PageRestorePlan.restore("page_2", 3L, 1));
        when(restoreRequester.send(any())).thenReturn(true);

        RestoreExecuteResponse response = execute();

        assertThat(response.rebuilding()).isFalse();
        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.deleteCount()).isEqualTo(1);
        assertThat(response.restoreCount()).isEqualTo(1);
        verify(lifecycle).finish(eq("op_restore"), any(), eq(true), any());
    }

    @Test
    @DisplayName("재작성 대상이 있으면 llmPipeline 결과를 기다린다")
    void waitsForRebuild() {
        givenValidPreview();
        givenPlan(PageRestorePlan.rebuild("page_1",
                List.of(new PageRestorePlan.Kept("op_b", "doc_B", "wiki/frag_b.md"))));
        when(restoreRequester.send(any())).thenReturn(true);

        RestoreExecuteResponse response = execute();

        assertThat(response.rebuilding()).isTrue();
        assertThat(response.status()).isEqualTo("rebuilding");
    }

    @Test
    @DisplayName("통지에 실패해도 반영은 유지하고 재시도 대상으로 남긴다")
    void keepsAppliedWhenNotifyFails() {
        givenValidPreview();
        givenPlan(PageRestorePlan.rebuild("page_1", List.of()));
        when(restoreRequester.send(any())).thenReturn(false);

        RestoreExecuteResponse response = execute();

        assertThat(response.status()).isEqualTo("notify_pending");
        verify(applier).apply(any(), any(), any(), any());
        verify(lifecycle).finish(eq("op_restore"), any(), eq(false), any());
    }

    @Test
    @DisplayName("조립 지시서에 이번 복구 작업의 콜백 주소를 싣는다")
    void sendsCallbackUrlOfThisRestore() {
        givenValidPreview();
        givenPlan(PageRestorePlan.rebuild("page_1", List.of()));
        when(restoreRequester.send(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.RestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.RestoreRun.class);
        verify(restoreRequester).send(captor.capture());
        assertThat(captor.getValue().resultCallbackUrl())
                .isEqualTo("http://backend:8080/api/ai-operations/op_restore/result");
        assertThat(captor.getValue().restoredFrom()).isEqualTo(TARGET);
    }

    private RestoreExecuteResponse execute() {
        return service.execute(WORKSPACE, USER, TARGET, TOKEN);
    }

    private void givenTarget(OperationType type) {
        OperationLog target = OperationLog.completed(TARGET, WORKSPACE, USER, type,
                "doc_A", "요약", 1, T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
    }

    private void givenValidPreview() {
        givenTarget(OperationType.ingest);
        when(scopeResolver.resolve(any())).thenReturn(Set.of("op_a3"));
        when(previewService.loadContributions(any())).thenReturn(Map.of());
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(true);
    }

    private void givenPlan(PageRestorePlan... pages) {
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of(pages)));
        OperationLog restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                "doc_A", TARGET, "{}", T);
        when(lifecycle.start(any(), anyString(), any())).thenReturn(restore);
        when(applier.apply(any(), any(), any(), any())).thenReturn(List.of());
    }
}
