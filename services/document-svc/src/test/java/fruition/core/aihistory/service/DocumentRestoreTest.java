package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentContentVersion;
import fruition.core.document.domain.DocumentContentVersionId;
import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.repository.DocumentContentVersionRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 문서 편집 되돌리기. Wiki와 달리 계산할 것이 없고, 과거 버전 본문으로 새 버전을 쌓는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentRestoreTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String DOCUMENT = "doc_A";
    private static final String TARGET = "op_7Kd3";
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Mock OperationChangeRepository operationChangeRepository;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentContentVersionRepository contentVersionRepository;
    @Mock DocumentService documentService;

    private DocumentRestorePlanner planner;
    private DocumentRestoreApplier applier;

    @BeforeEach
    void setUp() {
        planner = new DocumentRestorePlanner(operationChangeRepository, documentRepository);
        applier = new DocumentRestoreApplier(
                documentService, contentVersionRepository, operationChangeRepository);
    }

    @Nested
    @DisplayName("계획")
    class Planning {

        @Test
        @DisplayName("되돌릴 버전은 변경내역의 before_revision이다")
        void takesToVersionFromChange() {
            givenChange(5L, 6L);
            givenDocumentAtVersion(6);

            DocumentRestorePlan plan = planner.plan(target());

            assertThat(plan.documentId()).isEqualTo(DOCUMENT);
            assertThat(plan.toVersion()).isEqualTo(5);
        }

        @Test
        @DisplayName("from_version은 대상 작업이 만든 버전이 아니라 지금 버전이다")
        void fromVersionIsCurrentNotOperationResult() {
            // 그 작업 이후 사용자가 두 번 더 저장했다.
            givenChange(5L, 6L);
            givenDocumentAtVersion(8);

            assertThat(planner.plan(target()).fromVersion()).isEqualTo(8);
        }

        @Test
        @DisplayName("새로 만든 문서는 돌아갈 지점이 없어 거절한다")
        void rejectsWhenNoBeforeRevision() {
            givenChange(null, 1L);
            givenDocumentAtVersion(1);

            assertThatThrownBy(() -> planner.plan(target()))
                    .isInstanceOf(InvalidRestoreRequestException.class);
        }

        @Test
        @DisplayName("이미 그 버전이면 거절한다")
        void rejectsWhenAlreadyAtTargetVersion() {
            givenChange(5L, 6L);
            givenDocumentAtVersion(5);

            assertThatThrownBy(() -> planner.plan(target()))
                    .isInstanceOf(InvalidRestoreRequestException.class);
        }

        @Test
        @DisplayName("문서 변경내역이 없는 작업은 거절한다")
        void rejectsWhenNoDocumentChange() {
            when(operationChangeRepository.findByOperationIdOrderByIdAsc(TARGET))
                    .thenReturn(List.of(new OperationChange(TARGET, ResourceType.wiki_page, "wp_C3",
                            3L, 4L, ChangeType.updated, null, null, null)));

            assertThatThrownBy(() -> planner.plan(target()))
                    .isInstanceOf(InvalidRestoreRequestException.class);
        }
    }

    @Nested
    @DisplayName("반영")
    class Applying {

        @Test
        @DisplayName("과거 버전을 되살리지 않고 그 내용으로 새 버전을 쌓는다")
        void appendsNewVersionInsteadOfRewinding() {
            givenVersion(5, "# 원래 문단");
            when(documentService.saveContent(eq(WORKSPACE), eq(USER), eq(DOCUMENT),
                    eq("# 원래 문단"), eq(6L), isNull(), isNull()))
                    .thenReturn(new DocumentContentSaveResponse(
                            DOCUMENT, 7, "sha256:old", NOW, true));

            long newVersion = applier.apply(restore(), new DocumentRestorePlan(DOCUMENT, 6, 5));

            assertThat(newVersion).isEqualTo(7);
        }

        @Test
        @DisplayName("restored 변경내역을 남긴다")
        void recordsRestoredChange() {
            givenVersion(5, "# 원래 문단");
            when(documentService.saveContent(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new DocumentContentSaveResponse(DOCUMENT, 7, "sha256:old", NOW, true));

            applier.apply(restore(), new DocumentRestorePlan(DOCUMENT, 6, 5));

            ArgumentCaptor<OperationChange> captor = ArgumentCaptor.forClass(OperationChange.class);
            verify(operationChangeRepository).save(captor.capture());
            OperationChange change = captor.getValue();
            assertThat(change.getResourceType()).isEqualTo(ResourceType.document);
            assertThat(change.getChangeType()).isEqualTo(ChangeType.restored);
            assertThat(change.getBeforeRevision()).isEqualTo(6L);
            assertThat(change.getAfterRevision()).isEqualTo(7L);
        }

        @Test
        @DisplayName("내용이 이미 같아 저장이 일어나지 않으면 거절한다")
        void rejectsWhenContentUnchanged() {
            givenVersion(5, "# 원래 문단");
            when(documentService.saveContent(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new DocumentContentSaveResponse(DOCUMENT, 6, "sha256:same", NOW, false));

            assertThatThrownBy(() ->
                    applier.apply(restore(), new DocumentRestorePlan(DOCUMENT, 6, 5)))
                    .isInstanceOf(InvalidRestoreRequestException.class);
        }
    }

    @Nested
    @DisplayName("미리보기 토큰")
    class Token {

        @Test
        @DisplayName("지금 버전이 달라지면 서명이 어긋난다")
        void signatureBreaksWhenDocumentChanged() {
            PreviewTokenSigner signer = new PreviewTokenSigner("");
            String issued = signer.sign(TARGET, new DocumentRestorePlan(DOCUMENT, 6, 5));

            assertThat(signer.matches(issued, TARGET, new DocumentRestorePlan(DOCUMENT, 6, 5))).isTrue();
            // 미리보기 이후 누군가 문서를 저장해 버전이 7이 됐다.
            assertThat(signer.matches(issued, TARGET, new DocumentRestorePlan(DOCUMENT, 7, 5))).isFalse();
        }
    }

    // --- helpers ---

    private OperationLog target() {
        return OperationLog.completed(TARGET, WORKSPACE, USER, OperationType.document_edit,
                DOCUMENT, "AI 편집을 문서에 반영했습니다.", 1, NOW);
    }

    private OperationLog restore() {
        return OperationLog.applying("op_restore_1", WORKSPACE, USER, DOCUMENT, TARGET, "{}", NOW);
    }

    private void givenChange(Long before, Long after) {
        when(operationChangeRepository.findByOperationIdOrderByIdAsc(TARGET))
                .thenReturn(List.of(new OperationChange(TARGET, ResourceType.document, DOCUMENT,
                        before, after, ChangeType.updated, null, 12, 3)));
    }

    private void givenDocumentAtVersion(long version) {
        Document document = mock(Document.class);
        when(document.getCurrentVersion()).thenReturn(version);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT, WORKSPACE))
                .thenReturn(Optional.of(document));
    }

    private void givenVersion(long version, String markdown) {
        when(contentVersionRepository.findById(new DocumentContentVersionId(DOCUMENT, version)))
                .thenReturn(Optional.of(new DocumentContentVersion(
                        DOCUMENT, version, markdown, "sha256:old", USER, NOW)));
    }
}
