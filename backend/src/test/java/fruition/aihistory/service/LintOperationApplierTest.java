package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.domain.WikiPage;
import fruition.wiki.domain.WikiPageVersion;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.wiki.repository.WikiPageRepository;
import fruition.wiki.repository.WikiPageVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintOperationApplierTest {

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock WikiPageRepository wikiPageRepository;
    @Mock WikiPageVersionRepository versionRepository;
    @Mock WikiPageContributionRepository contributionRepository;
    @Mock LineCounter lineCounter;

    private LintOperationApplier applier;

    @BeforeEach
    void setUp() {
        applier = new LintOperationApplier(operationLogRepository, operationChangeRepository,
                wikiPageRepository, versionRepository, contributionRepository, lineCounter);
    }

    @Test
    void apply_updatesVersionWithoutCreatingContribution() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        WikiPage page = org.mockito.Mockito.mock(WikiPage.class);
        WikiPageVersion previous = org.mockito.Mockito.mock(WikiPageVersion.class);
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        when(wikiPageRepository.findByIdForUpdate("page_1")).thenReturn(Optional.of(page));
        when(page.getWorkspaceId()).thenReturn("ws_1");
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("page_1"))
                .thenReturn(Optional.of(previous));
        when(previous.getRevision()).thenReturn(3L);
        when(previous.getMarkdown()).thenReturn("# 이전 본문");
        when(versionRepository.findMaxRevision("page_1")).thenReturn(3L);
        when(contributionRepository.countByIdPageIdAndActiveTrue("page_1")).thenReturn(2L);
        when(lineCounter.count("page_1", 3L, "# 이전 본문", 4L, "# 정리된 본문"))
                .thenReturn(new LineCounter.LineCount(1, 1));
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of());

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 정리된 본문", "sha256:lint")),
                "payload-hash", Instant.now());

        ArgumentCaptor<WikiPageVersion> versionCaptor = ArgumentCaptor.forClass(WikiPageVersion.class);
        verify(versionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getRevision()).isEqualTo(4L);
        assertThat(versionCaptor.getValue().getContributionCount()).isEqualTo(2);
        assertThat(versionCaptor.getValue().getOperationId()).isEqualTo("op_lint_1");

        ArgumentCaptor<OperationChange> changeCaptor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getBeforeRevision()).isEqualTo(3L);
        assertThat(changeCaptor.getValue().getAfterRevision()).isEqualTo(4L);
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo(ChangeType.updated);
        verify(contributionRepository, never()).save(any());
        assertThat(operation.getStatus().name()).isEqualTo("succeeded");
        assertThat(operation.getChangedResourceCount()).isEqualTo(1);
    }

    @Test
    void apply_recordsCreatedForPageWithoutPreviousVersion() {
        OperationLog operation = OperationLog.processing(
                "op_lint_1", "ws_1", "user_1", OperationType.lint, null, Instant.now());
        WikiPage page = org.mockito.Mockito.mock(WikiPage.class);
        when(operationLogRepository.findById("op_lint_1")).thenReturn(Optional.of(operation));
        when(wikiPageRepository.findByIdForUpdate("page_1")).thenReturn(Optional.of(page));
        when(page.getWorkspaceId()).thenReturn("ws_1");
        when(versionRepository.findTopByIdPageIdOrderByIdRevisionDesc("page_1"))
                .thenReturn(Optional.empty());
        when(versionRepository.findMaxRevision("page_1")).thenReturn(0L);
        when(contributionRepository.countByIdPageIdAndActiveTrue("page_1")).thenReturn(0L);
        when(lineCounter.count("page_1", null, null, 1L, "# 새 개념"))
                .thenReturn(LineCounter.LineCount.none());
        OperationResultRequest request = new OperationResultRequest(
                "op_lint_1", "lint", "succeeded", "ws_1", "user_1", null,
                "Wiki lint를 실행했습니다.", List.of(), List.of());

        applier.apply("op_lint_1", request,
                List.of(new LintOperationApplier.LoadedPage(
                        "page_1", "wiki/ws_1/pages/page_1/ops/op_lint_1.md",
                        "# 새 개념", "sha256:lint")),
                "payload-hash", Instant.now());

        ArgumentCaptor<OperationChange> changeCaptor = ArgumentCaptor.forClass(OperationChange.class);
        verify(operationChangeRepository).save(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getBeforeRevision()).isNull();
        assertThat(changeCaptor.getValue().getAfterRevision()).isEqualTo(1L);
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo(ChangeType.created);
        verify(contributionRepository, never()).save(any());
    }
}
