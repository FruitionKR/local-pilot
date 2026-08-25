package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.OperationLogDetailResponse;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.authz.WorkspaceAccessGuard;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock ChangeDiffLoader diffLoader;

    private OperationQueryService service;

    @BeforeEach
    void setUp() {
        service = new OperationQueryService(operationLogRepository, operationChangeRepository,
                workspaceAccessGuard, diffLoader, new ObjectMapper());
    }

    @Test
    void list_filtersLintAndReturnsStoredSummary() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog lint = OperationLog.completed(
                OPERATION_ID, WORKSPACE_ID, USER_ID, OperationType.lint, null,
                "Wiki lint로 페이지 2개를 변경했습니다.", 2,
                Instant.parse("2026-08-04T01:00:00Z"));
        when(operationLogRepository.findPage(eq(WORKSPACE_ID), eq(OperationType.lint),
                eq(null), any(Instant.class), anyString(),
                eq(OperationStatus.succeeded), eq(OperationType.document_edit), anyCollection(),
                any(Pageable.class)))
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
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
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
    void detail_returnsQueuedRestorePlanAndResultCounts() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_ingest_1", """
                        {"plan":{"pages":[
                          {"pageId":"page_delete","action":"delete","contributionCount":0,"keepContributions":[]},
                          {"pageId":"page_rebuild","action":"rebuild","contributionCount":2,"keepContributions":[]}
                        ]}}
                        """, Instant.now());
        OperationChange deleted = change(1L, ResourceType.wiki_page, "page_delete", ChangeType.deleted);
        OperationChange delegated = change(2L, ResourceType.wiki_page, "page_rebuild", ChangeType.delegated);
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(restore));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(deleted, delegated));
        when(diffLoader.load(any())).thenReturn(List.of(
                new ChangeDiffLoader.Diff(List.of(), false),
                new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.restore()).isNotNull();
        assertThat(response.restore().plan().deleteCount()).isEqualTo(1);
        assertThat(response.restore().plan().rebuildCount()).isEqualTo(1);
        assertThat(response.restore().plan().pages())
                .extracting(OperationLogDetailResponse.PlanPage::pageId)
                .containsExactly("page_delete", "page_rebuild");
        assertThat(response.restore().result().deletedCount()).isEqualTo(1);
        assertThat(response.restore().result().rebuiltCount()).isZero();
    }

    @Test
    void detail_fallsBackToWikiCountsForLegacyManifestAndKeepsDocumentChange() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_ingest_1", "legacy-restore-manifest", Instant.now());
        OperationChange restoredPage = change(1L, ResourceType.wiki_page,
                "page_restore", ChangeType.restored);
        OperationChange restoredDocument = change(2L, ResourceType.document,
                "doc_A", ChangeType.restored);
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(restore));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(restoredPage, restoredDocument));
        when(diffLoader.load(any())).thenReturn(List.of(
                new ChangeDiffLoader.Diff(List.of(), false),
                new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.restore().plan().restoreCount()).isEqualTo(1);
        assertThat(response.restore().result().restoredCount()).isEqualTo(1);
        assertThat(response.restore().plan().pages())
                .extracting(OperationLogDetailResponse.PlanPage::pageId)
                .containsExactly("page_restore");
        assertThat(response.changes()).extracting(OperationLogDetailResponse.Change::resourceId)
                .containsExactly("page_restore", "doc_A");
        assertThat(response.changes()).filteredOn(change -> "doc_A".equals(change.resourceId()))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.resourceType()).isEqualTo("document");
                    assertThat(change.changeType()).isEqualTo("restored");
                });
    }

    @Test
    void detail_returnsSucceededRestoreResultFromChanges() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_ingest_1", """
                        {"plan":{"pages":[
                          {"pageId":"page_restore","action":"restore","contributionCount":1,"keepContributions":[]},
                          {"pageId":"page_rebuild","action":"rebuild","contributionCount":2,"keepContributions":[]}
                        ]}}
                        """, Instant.now());
        restore.complete(OperationStatus.succeeded, "복구 완료", 3, "hash", Instant.now());
        OperationChange restored = change(1L, ResourceType.wiki_page, "page_restore", ChangeType.restored);
        OperationChange rebuilt = change(2L, ResourceType.wiki_page, "page_rebuild", ChangeType.rebuilt);
        OperationChange link = change(3L, ResourceType.relation_link, "page_a|supports|page_b", ChangeType.link_restored);
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(restore));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(restored, rebuilt, link));
        when(diffLoader.load(any())).thenReturn(List.of(
                new ChangeDiffLoader.Diff(List.of(), false),
                new ChangeDiffLoader.Diff(List.of(), false),
                new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.restore().result().restoredCount()).isEqualTo(1);
        assertThat(response.restore().result().rebuiltCount()).isEqualTo(1);
        assertThat(response.restore().result().restoredLinkCount()).isEqualTo(1);
        assertThat(response.changes()).extracting(OperationLogDetailResponse.Change::resourceId)
                .containsExactly("page_restore", "page_rebuild", "page_a|supports|page_b");
    }

    @Test
    void detail_returnsFailedRestoreResultWithoutCallbackDetails() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_ingest_1", """
                        {"plan":{"pages":[
                          {"pageId":"page_rebuild","action":"rebuild","contributionCount":2,"keepContributions":[]}
                        ]},"callbackUrl":"http://internal/callback","previewToken":"secret"}
                        """, Instant.now());
        restore.complete(OperationStatus.failed, "실패", 1, "hash", Instant.now());
        OperationChange failed = change(1L, ResourceType.wiki_page, "page_rebuild", ChangeType.rebuild_failed);
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(restore));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(failed));
        when(diffLoader.load(any())).thenReturn(List.of(new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.restore().result().failedCount()).isEqualTo(1);
        assertThat(response.restore().plan().pages()).singleElement()
                .satisfies(page -> assertThat(page.pageId()).isEqualTo("page_rebuild"));
        assertThat(response.restore().toString()).doesNotContain("callbackUrl", "previewToken", "secret");
    }

    @Test
    void detail_returnsFailedActionAfterRestartWithoutManifestDetails() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        OperationLog restore = OperationLog.applying(OPERATION_ID, WORKSPACE_ID, USER_ID,
                "doc_A", "op_ingest_1", """
                        {"callbackUrl":"http://internal/callback","previewToken":"secret",
                         "providerPayload":{"objectKey":"wiki/private.json","content":"raw"}}
                        """, Instant.now());
        restore.complete(OperationStatus.partially_succeeded, "부분 실패", 1, "hash", Instant.now());
        OperationChange failed = change(1L, ResourceType.action, "op_lint_1", ChangeType.action_failed);
        when(failed.getChangeSummary()).thenReturn("restore_links: operation_log_missing");
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(restore));
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(OPERATION_ID))
                .thenReturn(List.of(failed));
        when(diffLoader.load(any())).thenReturn(List.of(new ChangeDiffLoader.Diff(List.of(), false)));

        var response = service.detail(WORKSPACE_ID, USER_ID, OPERATION_ID);

        assertThat(response.restore().result().failedCount()).isEqualTo(1);
        assertThat(response.changes()).singleElement().satisfies(change -> {
            assertThat(change.resourceType()).isEqualTo("action");
            assertThat(change.resourceId()).isEqualTo("op_lint_1");
            assertThat(change.changeType()).isEqualTo("action_failed");
            assertThat(change.changeSummary()).isEqualTo("restore_links: operation_log_missing");
        });
        assertThat(response.restore().plan().pages()).isEmpty();
        assertThat(response.toString()).doesNotContain(
                "callbackUrl", "previewToken", "secret", "providerPayload", "objectKey", "raw");
    }

    private OperationChange change(long id, ResourceType resourceType, String resourceId,
                                   ChangeType changeType) {
        OperationChange change = org.mockito.Mockito.mock(OperationChange.class);
        when(change.getId()).thenReturn(id);
        when(change.getResourceType()).thenReturn(resourceType);
        when(change.getResourceId()).thenReturn(resourceId);
        when(change.getChangeType()).thenReturn(changeType);
        when(change.getChangeSummary()).thenReturn(null);
        when(change.getAdditions()).thenReturn(null);
        when(change.getDeletions()).thenReturn(null);
        return change;
    }

    @Test
    void list_nextCursorCarriesOperationIdSoSameInstantIsNotSkipped() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        Instant sameInstant = Instant.parse("2026-08-20T05:33:40.036572Z");
        OperationLog first = OperationLog.completed(
                "op_zzz", WORKSPACE_ID, USER_ID, OperationType.ingest, null, "A", 1, sameInstant);
        OperationLog second = OperationLog.completed(
                "op_aaa", WORKSPACE_ID, USER_ID, OperationType.ingest, null, "B", 1, sameInstant);
        // size+1건을 돌려주면 서비스가 다음 페이지가 있다고 판단한다.
        when(operationLogRepository.findPage(eq(WORKSPACE_ID), eq(null), eq(null),
                any(Instant.class), anyString(),
                eq(OperationStatus.succeeded), eq(OperationType.document_edit), anyCollection(),
                any(Pageable.class)))
                .thenReturn(List.of(first, second));

        var response = service.list(WORKSPACE_ID, USER_ID, null, null, null, 1);

        assertThat(response.nextCursor()).isEqualTo(sameInstant + ",op_zzz");
    }

    @Test
    void list_splitsCursorIntoInstantAndOperationId() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        when(operationLogRepository.findPage(eq(WORKSPACE_ID), eq(null), eq(null),
                eq(Instant.parse("2026-08-20T05:33:40.036572Z")), eq("op__hIetMtPO1nVEXY3cBvAdw"),
                eq(OperationStatus.succeeded), eq(OperationType.document_edit),
                anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        var response = service.list(WORKSPACE_ID, USER_ID, null, null,
                "2026-08-20T05:33:40.036572Z,op__hIetMtPO1nVEXY3cBvAdw", 20);

        assertThat(response.logs()).isEmpty();
    }

    @Test
    void list_rejectsCursorWithoutOperationId() {
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);

        assertThatThrownBy(() -> service.list(
                WORKSPACE_ID, USER_ID, null, null, "2026-08-20T05:33:40.036572Z", 20))
                .hasMessageContaining("커서 형식이 올바르지 않습니다");
    }

    @Test
    void list_rejectsNonMemberBeforeReadingLogs() {
        doThrow(new WorkspaceNotFoundException(WORKSPACE_ID))
                .when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);

        assertThatThrownBy(() -> service.list(
                WORKSPACE_ID, USER_ID, "lint", null, null, 20))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(operationLogRepository, never()).findPage(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
