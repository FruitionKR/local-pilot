package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentExportServiceTest {

    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String USER_ID = "member_1";

    @Mock DocumentRepository documentRepository;
    @Mock DocumentEditStateRepository editStateRepository;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock MongoDocumentEditStore mongoDocumentEditStore;

    DocumentExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new DocumentExportService(
                documentRepository, editStateRepository,
                workspaceAccessGuard, mongoDocumentEditStore);
    }

    @Test
    void exportMarkdown_memberDownloadsLatestUtf8WithoutChangingState() {
        Document document = new Document(
                "doc_export", WORKSPACE_ID, "owner_1", "회의 결과.md",
                "text/markdown", 10, null, null, "direct");
        document.initializeDirectMarkdown("hash", 10, 3);
        DocumentEditState editState = new DocumentEditState(
                document.getId(), "# 최신 회의 결과\n한글 본문", "hash");
        long versionBefore = document.getCurrentVersion();
        var updatedAtBefore = document.getUpdatedAt();
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                document.getId(), WORKSPACE_ID)).thenReturn(Optional.of(document));
        when(editStateRepository.findById(document.getId())).thenReturn(Optional.of(editState));

        DocumentExportResult result =
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, document.getId());

        assertThat(result.filename()).isEqualTo("회의 결과.md");
        assertThat(new String(result.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("# 최신 회의 결과\n한글 본문");
        assertThat(document.getCurrentVersion()).isEqualTo(versionBefore);
        assertThat(document.getUpdatedAt()).isEqualTo(updatedAtBefore);
        verify(documentRepository, never()).save(document);
        verify(editStateRepository, never()).save(editState);
    }

    @Test
    void exportMarkdown_originalOrMissingEditState_returnsNotFound() {
        Document original = new Document(
                "doc_pdf", WORKSPACE_ID, USER_ID, "자료.pdf",
                "application/pdf", 10, "sources/doc_pdf/original", "hash");
        doNothing().when(workspaceAccessGuard).requireMember(WORKSPACE_ID, USER_ID);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                original.getId(), WORKSPACE_ID)).thenReturn(Optional.of(original));

        assertThatThrownBy(() ->
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, original.getId()))
                .isInstanceOf(DocumentNotFoundException.class);

        Document editable = new Document(
                "doc_no_state", WORKSPACE_ID, USER_ID, "문서.md",
                "text/markdown", 0, null, null, "direct");
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                editable.getId(), WORKSPACE_ID)).thenReturn(Optional.of(editable));
        when(editStateRepository.findById(editable.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                exportService.exportMarkdown(WORKSPACE_ID, USER_ID, editable.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
