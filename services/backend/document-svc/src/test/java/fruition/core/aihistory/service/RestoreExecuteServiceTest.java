package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestoreExecuteResponse;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.repository.PipelineRestoreRequester;
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
import static org.mockito.Mockito.lenient;
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
    @Mock LintRestorePlanner lintRestorePlanner;
    @Mock PreviewTokenSigner tokenSigner;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock DocumentRestorePlanner documentPlanner;
    @Mock DocumentRestoreApplier documentApplier;
    @Mock RestoreApplier applier;
    @Mock PipelineRestoreRequester restoreRequester;
    @Mock RestoreTargetValidator validator;

    private RestoreExecuteService service;

    @BeforeEach
    void setUp() {
        service = new RestoreExecuteService(previewService, scopeResolver, planner,
                lintRestorePlanner, tokenSigner,
                lifecycle, documentPlanner, documentApplier, applier, restoreRequester,
                validator, new ObjectMapper(), "http://backend:8080");
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

        verify(applier, never()).apply(any(), any(), any(), any(), any());
        verify(lifecycle, never()).start(any(), anyString(), any());
    }

    @Test
    @DisplayName("되돌릴 수 없는 작업 유형은 거절한다")
    void rejectsNonRestorableOperationType() {
        givenTarget(OperationType.restore);
        org.mockito.Mockito.doThrow(new InvalidRestoreRequestException("되돌릴 수 없는 작업입니다."))
                .when(validator).requireRestorable(any());

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        verify(applier, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("문서 편집은 Wiki 경로를 타지 않고 문서 되돌리기로 간다")
    void routesDocumentEditToDocumentRestore() {
        givenTarget(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                "doc_A", TARGET, "{}", T);
        when(documentPlanner.plan(any())).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(any(), anyString(), any())).thenReturn(restore);
        when(documentApplier.apply(any(), any())).thenReturn(7L);

        RestoreExecuteResponse response = execute();

        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.rebuilding()).isFalse();
        verify(documentApplier).apply(restore, plan);
        verify(lifecycle).finishDocument(eq("op_restore"), eq(5L), eq(7L), any());
        verify(applier, never()).apply(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("문서 편집도 미리보기 이후 바뀌었으면 409다")
    void rejectsStaleDocumentToken() {
        givenTarget(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        when(documentPlanner.plan(any())).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(false);

        assertThatThrownBy(() -> execute())
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(documentApplier, never()).apply(any(), any());
    }

    @Test
    @DisplayName("되돌릴 Wiki 페이지가 없으면 작업을 만들지 않는다")
    void rejectsEmptyPlan() {
        givenValidPreview();
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of()));
        when(validator.requireApplicable(any(), any()))
                .thenThrow(new InvalidRestoreRequestException("되돌릴 Wiki 페이지가 없습니다."));

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        verify(lifecycle, never()).start(any(), anyString(), any());
    }

    @Test
    @DisplayName("재작성 대상이 없어도 llmPipeline 결과를 기다린다")
    void waitsEvenWhenNothingToRebuild() {
        givenValidPreview();
        givenPlan(PageRestorePlan.delete("page_1"), PageRestorePlan.restore("page_2", 3L, "op_a1", 1));
        givenSourcePage("page_2");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        RestoreExecuteResponse response = execute();

        // llmPipeline 은 재작성할 페이지가 없어도 링크·임베딩을 정리하고 결과를 보내온다.
        // 미리 완료로 확정하면 그 콜백이 종료된 작업에 도착해 409 로 거절된다.
        assertThat(response.status()).isEqualTo("rebuilding");
        assertThat(response.rebuilding()).isTrue();
        assertThat(response.rebuildCount()).isZero();
        assertThat(response.deleteCount()).isEqualTo(1);
        assertThat(response.restoreCount()).isEqualTo(1);
        verify(lifecycle).finish(eq("op_restore"), any(), eq(true), any());
    }

    @Test
    @DisplayName("재작성 대상이 있으면 llmPipeline 결과를 기다린다")
    void waitsForRebuild() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1),
                PageRestorePlan.rebuild("page_1",
                        List.of(new PageRestorePlan.Kept("op_b", "doc_B", "wiki/frag_b.md"))));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        RestoreExecuteResponse response = execute();

        assertThat(response.rebuilding()).isTrue();
        assertThat(response.status()).isEqualTo("rebuilding");
    }

    @Test
    @DisplayName("통지에 실패해도 반영은 유지하고 재시도 대상으로 남긴다")
    void keepsAppliedWhenNotifyFails() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1),
                PageRestorePlan.rebuild("page_1", List.of()));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(false);

        RestoreExecuteResponse response = execute();

        assertThat(response.status()).isEqualTo("notify_pending");
        verify(applier).apply(any(), any(), any(), any(), any());
        verify(lifecycle).finish(eq("op_restore"), any(), eq(false), any());
    }

    @Test
    @DisplayName("반영 중 실패하면 복구 작업을 실패로 확정하고 원래 예외를 그대로 던진다")
    void failsRestoreOperationWhenApplyThrows() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1));
        givenSourcePage("wp_S_A");
        InvalidRestoreRequestException failure =
                new InvalidRestoreRequestException("Wiki 페이지를 찾을 수 없습니다: pageId=wp_S_A");
        org.mockito.Mockito.doThrow(failure).when(applier).apply(any(), any(), any(), any(), any());

        // applying 커밋 이후 반영이 실패해도 캐치되지 않은 채 그대로 두면 이 작업이 applying에
        // 영구히 남는다. lifecycle.fail 로 실패를 확정하고, 호출부에는 원래 예외를 그대로 전달해야 한다.
        assertThatThrownBy(() -> execute()).isSameAs(failure);

        verify(lifecycle).fail(eq("op_restore"), anyString(), any());
        verify(lifecycle, never()).finish(any(), any(), anyBoolean(), any());
        verify(restoreRequester, never()).sendIngestRestore(any());
    }

    @Test
    @DisplayName("통지 전송 중 실패해도 복구 작업을 실패로 확정한다")
    void failsRestoreOperationWhenNotifyThrows() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1));
        givenSourcePage("wp_S_A");
        RuntimeException failure = new RuntimeException("커넥션 실패");
        when(restoreRequester.sendIngestRestore(any())).thenThrow(failure);

        assertThatThrownBy(() -> execute()).isSameAs(failure);

        verify(lifecycle).fail(eq("op_restore"), anyString(), any());
        verify(lifecycle, never()).finish(any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("조립 지시서에 이번 복구 작업의 콜백 주소를 싣는다")
    void sendsCallbackUrlOfThisRestore() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1),
                PageRestorePlan.rebuild("page_1", List.of()));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.IngestRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.IngestRestoreRun.class);
        verify(restoreRequester).sendIngestRestore(captor.capture());
        assertThat(captor.getValue().resultCallbackUrl())
                .isEqualTo("http://backend:8080/api/ai-operations/op_restore/result");
        assertThat(captor.getValue().cancelOperationIds()).containsExactly("op_a3");
    }

    @Test
    @DisplayName("ingest 지시서에 source page와 되돌릴 시점을 싣는다")
    void sendsSourcePageAndRestorePoint() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1),
                PageRestorePlan.rebuild("wp_C7",
                        List.of(new PageRestorePlan.Kept("op_b", "doc_B", "wiki/frag_b.json"))),
                PageRestorePlan.delete("wp_C8"));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.IngestRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.IngestRestoreRun.class);
        verify(restoreRequester).sendIngestRestore(captor.capture());
        PipelineRestoreRequester.IngestRestoreRun run = captor.getValue();

        assertThat(run.sourcePage().pageId()).isEqualTo("wp_S_A");
        assertThat(run.restoreToOperationId()).isEqualTo("op_a1");
        // source page 는 별도 필드로 넘기므로 삭제 목록에서 뺀다.
        assertThat(run.deletedPages()).containsExactly("wp_C8");
        assertThat(run.rebuildPages()).singleElement()
                .satisfies(page -> {
                    assertThat(page.pageId()).isEqualTo("wp_C7");
                    // object_key 는 보내지 않는다. llmPipeline 이 같은 규칙으로 만든다.
                    assertThat(page.keepContributions()).containsExactly(
                            new PipelineRestoreRequester.Kept("op_b", "doc_B"));
                });
    }

    @Test
    @DisplayName("남는 기여가 없으면 되돌릴 시점이 null이라 llmPipeline이 source page를 지운다")
    void sendsNullRestorePointWhenSourcePageDeleted() {
        givenValidPreview();
        givenPlan(PageRestorePlan.delete("wp_S_A"));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.IngestRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.IngestRestoreRun.class);
        verify(restoreRequester).sendIngestRestore(captor.capture());
        assertThat(captor.getValue().restoreToOperationId()).isNull();
        assertThat(captor.getValue().sourcePage().pageId()).isEqualTo("wp_S_A");
        assertThat(captor.getValue().deletedPages()).isEmpty();
    }

    @Test
    @DisplayName("lint 되돌리기는 다른 엔드포인트로 간다")
    void routesLintToLintEndpoint() {
        givenTarget(OperationType.lint);
        when(scopeResolver.resolve(any())).thenReturn(Set.of(TARGET));
        PageRestorePlan rebuild = PageRestorePlan.rebuild("wp_C3", List.of(
                new PageRestorePlan.Kept(
                        "op_ingest_1", "doc_1", "wiki/ws_1/pages/wp_C3/ops/op_ingest_1.json")));
        PageRestorePlan delete = PageRestorePlan.delete("wp_C4");
        RestorePlan lintPlan = new RestorePlan(List.of(rebuild, delete));
        when(lintRestorePlanner.plan(any())).thenReturn(
                new LintRestorePlanner.Context(lintPlan, Map.of("wp_C3", List.of())));
        when(tokenSigner.matches(TOKEN, TARGET, Map.of("wp_C3", List.of()))).thenReturn(true);
        OperationLog restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                null, TARGET, "{}", T);
        when(lifecycle.start(any(), anyString(), any())).thenReturn(restore);
        when(validator.requireApplicable(any(), any())).thenReturn(null);
        when(restoreRequester.sendLintRestore(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.LintRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.LintRestoreRun.class);
        verify(restoreRequester).sendLintRestore(captor.capture());
        assertThat(captor.getValue().targetOperationId()).isEqualTo(TARGET);
        assertThat(captor.getValue().deletedPages()).containsExactly("wp_C4");
        assertThat(captor.getValue().rebuildPages()).singleElement().satisfies(page -> {
            assertThat(page.pageId()).isEqualTo("wp_C3");
            assertThat(page.keepContributions()).containsExactly(
                    new PipelineRestoreRequester.Kept("op_ingest_1", "doc_1"));
        });
        verify(restoreRequester, never()).sendIngestRestore(any());
    }

    @Test
    @DisplayName("검증에 걸리면 반영 전에 멈춘다")
    void rejectsBeforeApplyingWhenNotApplicable() {
        givenValidPreview();
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(
                List.of(PageRestorePlan.rebuild("wp_C7", List.of()))));
        when(validator.requireApplicable(any(), any()))
                .thenThrow(new InvalidRestoreRequestException("되돌릴 대상에 원문 페이지가 없습니다."));

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        // 뒤에서 걸리면 이미 DB 가 바뀐 뒤라 되돌릴 수 없다.
        verify(lifecycle, never()).start(any(), anyString(), any());
        verify(applier, never()).apply(any(), any(), any(), any(), any());
        verify(restoreRequester, never()).sendIngestRestore(any());
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

    /** 검증기가 돌려주는 원문 페이지. 실제 판별 규칙은 RestoreTargetValidatorTest 가 다룬다. */
    private void givenSourcePage(String pageId) {
        when(validator.requireApplicable(any(), any()))
                .thenAnswer(call -> ((RestorePlan) call.getArgument(1)).pages().stream()
                        .filter(page -> page.pageId().equals(pageId))
                        .findFirst().orElseThrow());
    }

    private void givenPlan(PageRestorePlan... pages) {
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of(pages)));
        OperationLog restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                "doc_A", TARGET, "{}", T);
        when(lifecycle.start(any(), anyString(), any())).thenReturn(restore);
        // source page 조회는 ingest 경로에서만 일어난다. 필요한 테스트가 givenSourcePage 로 채운다.
        lenient().when(validator.requireApplicable(any(), any())).thenReturn(null);
    }
}
