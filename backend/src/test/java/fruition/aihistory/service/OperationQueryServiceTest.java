package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationQueryServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";
    private static final String OPERATION_ID = "op_lint_1";

    @Mock OperationLogRepository operationLogRepository;
    @Mock OperationChangeRepository operationChangeRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock ChangeDiffLoader diffLoader;

    private OperationQueryService service;

    @BeforeEach
    void setUp() {
        service = new OperationQueryService(operationLogRepository, operationChangeRepository,
                workspaceMemberRepository, diffLoader);
    }

    @Test
    void list_filtersLintAndReturnsStoredSummary() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID))
                .thenReturn(true);
        OperationLog lint = OperationLog.completed(
                OPERATION_ID, WORKSPACE_ID, USER_ID, OperationType.lint, null,
                "Wiki lint로 페이지 2개를 변경했습니다.", 2,
                Instant.parse("2026-08-04T01:00:00Z"));
        when(operationLogRepository.findPage(eq(WORKSPACE_ID), eq(OperationType.lint),
                eq(null), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(lint));

        var response = service.list(WORKSPACE_ID, USER_ID, "lint", null, null, 20);

        assertThat(response.logs()).singleElement().satisfies(item -> {
            assertThat(item.operationType()).isEqualTo("lint");
            assertThat(item.status()).isEqualTo("succeeded");
            assertThat(item.targetDocumentId()).isNull();
            assertThat(item.changedResourceCount()).isEqualTo(2);
            assertThat(item.summary()).isEqualTo("Wiki lint로 페이지 2개를 변경했습니다.");
        });
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void detail_returnsLintRevisionAndDiff() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID))
                .thenReturn(true);
        OperationLog lint = OperationLog.completed(
                OPERATION_ID, WORKSPACE_ID, USER_ID, OperationType.lint, null,
                "Wiki lint로 페이지 1개를 변경했습니다.", 1, Instant.now());
        OperationChange change = org.mockito.Mockito.mock(OperationChange.class);
        when(change.getId()).thenReturn(1L);
        when(change.getResourceType()).thenReturn(ResourceType.wiki_page);
        when(change.getResourceId()).thenReturn("page_1");
        when(change.getBeforeRevision()).thenReturn(3L);
        when(change.getAfterRevision()).thenReturn(4L);
        when(change.getChangeType()).thenReturn(ChangeType.updated);
        when(change.getAdditions()).thenReturn(2);
        when(change.getDeletions()).thenReturn(1);
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(lint));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(change));
        when(diffLoader.load(List.of(change)))
                .thenReturn(List.of(new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.operationType()).isEqualTo("lint");
        assertThat(response.targetDocumentId()).isNull();
        assertThat(response.changes()).singleElement().satisfies(item -> {
            assertThat(item.resourceType()).isEqualTo("wiki_page");
            assertThat(item.beforeRevision()).isEqualTo(3L);
            assertThat(item.afterRevision()).isEqualTo(4L);
            assertThat(item.changeType()).isEqualTo("updated");
            assertThat(item.hunks()).isEmpty();
        });
    }

    @Test
    void list_rejectsNonMemberBeforeReadingLogs() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> service.list(
                WORKSPACE_ID, USER_ID, "lint", null, null, 20))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(operationLogRepository, never()).findPage(
                any(), any(), any(), any(), any());
    }
}
