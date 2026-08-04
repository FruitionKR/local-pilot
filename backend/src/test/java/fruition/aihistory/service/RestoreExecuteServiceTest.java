package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.DocumentRestorePlan;
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
    @Mock PreviewTokenSigner tokenSigner;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock DocumentRestorePlanner documentPlanner;
    @Mock DocumentRestoreApplier documentApplier;
    @Mock RestoreApplier applier;
    @Mock PipelineRestoreRequester restoreRequester;
    @Mock fruition.wiki.repository.WikiPageRepository wikiPageRepository;

    private RestoreExecuteService service;

    @BeforeEach
    void setUp() {
        service = new RestoreExecuteService(previewService, scopeResolver, planner, tokenSigner,
                lifecycle, documentPlanner, documentApplier, applier, restoreRequester,
                wikiPageRepository, new ObjectMapper(), "http://backend:8080");
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
    @DisplayName("되돌릴 수 없는 작업 유형은 거절한다")
    void rejectsNonRestorableOperationType() {
        givenTarget(OperationType.restore);

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        verify(applier, never()).apply(any(), any(), any(), any());
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
        verify(applier, never()).apply(any(), any(), any(), any());
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
        verify(applier).apply(any(), any(), any(), any());
        verify(lifecycle).finish(eq("op_restore"), any(), eq(false), any());
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
        when(previewService.loadContributions(any())).thenReturn(Map.of());
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(true);
        givenPlan(PageRestorePlan.rebuild("wp_C3", List.of()));
        when(restoreRequester.sendLintRestore(any())).thenReturn(true);

        execute();

        ArgumentCaptor<PipelineRestoreRequester.LintRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.LintRestoreRun.class);
        verify(restoreRequester).sendLintRestore(captor.capture());
        assertThat(captor.getValue().targetOperationId()).isEqualTo(TARGET);
        verify(restoreRequester, never()).sendIngestRestore(any());
    }

    @Test
    @DisplayName("원문 페이지를 못 찾으면 반영 전에 거절한다")
    void rejectsBeforeApplyingWhenSourcePageMissing() {
        givenValidPreview();
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(
                List.of(PageRestorePlan.rebuild("wp_C7", List.of()))));
        when(wikiPageRepository.findIdsByPageType(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> execute())
                .isInstanceOf(InvalidRestoreRequestException.class);

        // llmPipeline 의 source_page 는 필수라 없이 보내면 400 이다. 그때는 이미 DB 반영이
        // 끝나 되돌릴 수 없으므로, 아무것도 바꾸기 전에 멈춰야 한다.
        verify(lifecycle, never()).start(any(), anyString(), any());
        verify(applier, never()).apply(any(), any(), any(), any());
        verify(restoreRequester, never()).sendIngestRestore(any());
    }

    @Test
    @DisplayName("원문 페이지는 링크가 아니라 page_type으로 찾는다")
    void findsSourcePageByPageType() {
        givenValidPreview();
        givenPlan(PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1));
        givenSourcePage("wp_S_A");
        when(restoreRequester.sendIngestRestore(any())).thenReturn(true);

        execute();

        // document_wiki_links 는 llmPipeline 이 관리해 문서 재처리 때 지워질 수 있다.
        verify(wikiPageRepository).findIdsByPageType(
                List.of("wp_S_A"), fruition.wiki.domain.WikiPageType.source);
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

    private void givenSourcePage(String pageId) {
        when(wikiPageRepository.findIdsByPageType(
                any(), eq(fruition.wiki.domain.WikiPageType.source)))
                .thenReturn(List.of(pageId));
    }

    private void givenPlan(PageRestorePlan... pages) {
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of(pages)));
        OperationLog restore = OperationLog.applying("op_restore", WORKSPACE, USER,
                "doc_A", TARGET, "{}", T);
        when(lifecycle.start(any(), anyString(), any())).thenReturn(restore);
        // source page 조회는 ingest 경로에서만 일어난다. 필요한 테스트가 givenSourcePage 로 채운다.
        lenient().when(wikiPageRepository.findIdsByPageType(any(), any())).thenReturn(List.of());
    }
}
