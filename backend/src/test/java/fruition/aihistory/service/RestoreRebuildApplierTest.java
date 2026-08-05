package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.dto.OperationResultResponse;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.exception.InvalidCallbackPayloadException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재조립 결과 반영. 기여를 새로 만들지 않고 지시서에 적힌 목표 기여 수를 그대로 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestoreRebuildApplierTest {

    private static final String OPERATION_ID = "op_restore_1";
    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";
    private static final String PAGE_ID = "C3";
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock WikiPageRepository wikiPageRepository;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock LineCounter lineCounter;
    @Mock WikiPage wikiPage;

    private RestoreRebuildApplier applier;

    @BeforeEach
    void setUp() {
        applier = new RestoreRebuildApplier(operationLogRepository, operationChangeRepository,
                wikiPageRepository, versionRepository, lineCounter, new ObjectMapper());
        when(wikiPage.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(wikiPageRepository.findById(PAGE_ID)).thenReturn(Optional.of(wikiPage));
        when(lineCounter.count(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), anyString()))
                .thenReturn(new LineCounter.LineCount(null, null));
    }

    @Test
    @DisplayName("contribution_count는 다시 세지 않고 지시서 값을 쓴다")
    void usesContributionCountFromManifest() {
        givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        givenPreviousRevision(4, "sha256:old");

        applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW);

        ArgumentCaptor<WikiPageVersion> captor = ArgumentCaptor.forClass(WikiPageVersion.class);
        verify(versionRepository).save(captor.capture());
        assertThat(captor.getValue().getContributionCount()).isEqualTo(2);
        assertThat(captor.getValue().getRevision()).isEqualTo(5);
    }

    @Test
    @DisplayName("성공한 페이지는 rebuilt로 기록한다")
    void recordsRebuilt() {
        givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        givenPreviousRevision(4, "sha256:old");

        applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW);

        ArgumentCaptor<OperationChange> captor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(ChangeType.rebuilt);
        assertThat(captor.getValue().getBeforeRevision()).isEqualTo(4L);
        assertThat(captor.getValue().getAfterRevision()).isEqualTo(5L);
    }

    @Test
    @DisplayName("실패한 페이지는 본문을 건드리지 않고 사유만 남긴다")
    void recordsFailureWithoutTouchingContent() {
        OperationLog operation = givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        givenPreviousRevision(4, "sha256:old");
        OperationResultRequest request = new OperationResultRequest(
                OPERATION_ID, "restore", "partially_succeeded", WORKSPACE_ID, USER_ID, "doc_A",
                "재조립 실패 1건", List.of(),
                List.of(new OperationResultRequest.FailedPage(PAGE_ID, "contribution_missing")));

        OperationResultResponse response = applier.apply(OPERATION_ID, request, List.of(), "hash", NOW);

        ArgumentCaptor<OperationChange> captor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(ChangeType.rebuild_failed);
        assertThat(captor.getValue().getChangeSummary()).isEqualTo("contribution_missing");
        assertThat(captor.getValue().getAfterRevision()).isNull();
        verify(versionRepository, never()).save(any());
        assertThat(response.status()).isEqualTo("partially_succeeded");
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.partially_succeeded);
    }

    @Test
    @DisplayName("전량 성공이면 succeeded로 확정한다")
    void completesWhenAllSucceeded() {
        OperationLog operation = givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        givenPreviousRevision(4, "sha256:old");

        applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW);

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.succeeded);
    }

    @Test
    @DisplayName("같은 결과가 다시 오면 새 버전을 만들지 않는다")
    void skipsWhenContentUnchanged() {
        givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        givenPreviousRevision(5, "sha256:new");

        applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW);

        verify(versionRepository, never()).save(any());
        verify(operationChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("실패 기록이 이미 있으면 다시 만들지 않는다")
    void skipsDuplicateFailure() {
        givenOperation(manifest(PageRestorePlan.rebuild(PAGE_ID, keptOf(2))));
        when(operationChangeRepository.existsByOperationIdAndResourceIdAndChangeType(
                OPERATION_ID, PAGE_ID, ChangeType.rebuild_failed)).thenReturn(true);
        OperationResultRequest request = new OperationResultRequest(
                OPERATION_ID, "restore", "partially_succeeded", WORKSPACE_ID, USER_ID, "doc_A",
                null, List.of(),
                List.of(new OperationResultRequest.FailedPage(PAGE_ID, "contribution_missing")));

        applier.apply(OPERATION_ID, request, List.of(), "hash", NOW);

        verify(operationChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("요청하지 않은 페이지가 오면 거절한다")
    void rejectsPageNotInManifest() {
        givenOperation(manifest(PageRestorePlan.rebuild("C9", keptOf(2))));
        givenPreviousRevision(4, "sha256:old");

        assertThatThrownBy(() ->
                applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW))
                .isInstanceOf(InvalidCallbackPayloadException.class);

        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Backend가 되돌린 페이지를 llmPipeline도 보내오지만 내용이 같아 건너뛴다")
    void skipsPageAlreadyRestoredByBackend() {
        // source page 가 그렇다. Backend 는 로컬에서 되돌리고, llmPipeline 은 자기 사본을 만들어 보고한다.
        givenOperation(manifest(PageRestorePlan.restore(PAGE_ID, 3L, "op_a1", 1)));
        givenPreviousRevision(4, "sha256:same");

        applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:same")), "hash", NOW);

        verify(versionRepository, never()).save(any());
        verify(operationChangeRepository, never()).save(any());
    }

    @Test
    @DisplayName("지시서에 아예 없는 페이지는 거절한다")
    void rejectsPageNotInPlan() {
        givenOperation(manifest(PageRestorePlan.delete("C9")));
        givenPreviousRevision(4, "sha256:old");

        assertThatThrownBy(() ->
                applier.apply(OPERATION_ID, request(), List.of(rebuilt("sha256:new")), "hash", NOW))
                .isInstanceOf(InvalidCallbackPayloadException.class);
    }

    // --- helpers ---

    private OperationLog givenOperation(String manifest) {
        OperationLog operation = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_a2", manifest, NOW);
        operation.moveTo(OperationStatus.rebuilding);
        when(operationLogRepository.findById(OPERATION_ID)).thenReturn(Optional.of(operation));
        return operation;
    }

    /** 지시서는 JSON으로 보관하므로 직렬화·역직렬화를 거쳐야 실제와 같다. */
    private String manifest(PageRestorePlan... pages) {
        try {
            return new ObjectMapper().writeValueAsString(new RestorePlan(List.of(pages)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<PageRestorePlan.Kept> keptOf(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PageRestorePlan.Kept("op_" + i, "doc_" + i, "wiki/frag_" + i + ".json"))
                .toList();
    }

    private void givenPreviousRevision(long revision, String contentHash) {
        WikiPageVersion previous = new WikiPageVersion(PAGE_ID, revision, 3, "# 이전 본문",
                "wiki/old.md", contentHash, "op_prev", USER_ID, NOW);
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc(PAGE_ID))
                .thenReturn(Optional.of(previous));
        when(versionRepository.findMaxRevision(PAGE_ID)).thenReturn(revision);
    }

    private RestoreRebuildApplier.RebuiltPage rebuilt(String contentHash) {
        return new RestoreRebuildApplier.RebuiltPage(
                PAGE_ID, "wiki/ws_1/pages/C3/ops/" + OPERATION_ID + ".md", "# 다시 만든 C3", contentHash);
    }

    private OperationResultRequest request() {
        return new OperationResultRequest(
                OPERATION_ID, "restore", "succeeded", WORKSPACE_ID, USER_ID, "doc_A",
                "재조립 1건", List.of(new OperationResultRequest.ChangedPage(
                        PAGE_ID, "concept", "wiki/ws_1/pages/C3/ops/" + OPERATION_ID + ".md",
                        null, "sha256:new", null)),
                List.of());
    }
}
