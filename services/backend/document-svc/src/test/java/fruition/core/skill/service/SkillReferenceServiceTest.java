package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.mongo.MongoDocumentEditState;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.skill.exception.SkillReferenceDocumentTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillReferenceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock DocumentRepository documentRepository;
    @Mock MongoDocumentEditStore mongoDocumentEditStore;
    @Mock Document document;
    @Mock MongoDocumentEditState editState;

    private SkillReferenceService service;

    @BeforeEach
    void setUp() {
        service = new SkillReferenceService(workspaceAccessGuard, documentRepository, mongoDocumentEditStore);
    }

    @Test
    void read_returnsLatestMongoCanonicalMarkdownWithinScope() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editState.getWorkspaceId()).thenReturn("ws_1");
        when(editState.getMarkdown()).thenReturn("# 최신 본문");
        when(mongoDocumentEditStore.findState("doc_1")).thenReturn(Optional.of(editState));

        var response = service.read("ws_1", "user_1", "doc_1");

        assertThat(response.documentRole()).isEqualTo("EDITABLE");
        assertThat(response.markdown()).isEqualTo("# 최신 본문");
    }

    @Test
    void read_rejectsEditableCanonicalMarkdownOverThirtyThousandCharacters() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editState.getWorkspaceId()).thenReturn("ws_1");
        when(editState.getMarkdown()).thenReturn("가".repeat(40001));
        when(mongoDocumentEditStore.findState("doc_1")).thenReturn(Optional.of(editState));

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1"))
                .isInstanceOf(SkillReferenceDocumentTooLargeException.class);
    }

    @Test
    void read_returnsOriginalRoleWithoutReadingCoreMarkdown() {
        when(document.getDocumentRole()).thenReturn(DocumentRole.ORIGINAL);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));

        var response = service.read("ws_1", "user_1", "doc_1");

        assertThat(response.documentRole()).isEqualTo("ORIGINAL");
        assertThat(response.markdown()).isNull();
        verifyNoInteractions(mongoDocumentEditStore);
    }

    @Test
    void read_rejectsMongoStateFromDifferentWorkspace() {
        when(document.getId()).thenReturn("doc_1");
        when(document.getDocumentRole()).thenReturn(DocumentRole.EDITABLE);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", "ws_1"))
                .thenReturn(Optional.of(document));
        when(editState.getWorkspaceId()).thenReturn("ws_2");
        when(mongoDocumentEditStore.findState("doc_1")).thenReturn(Optional.of(editState));

        assertThatThrownBy(() -> service.read("ws_1", "user_1", "doc_1"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void read_failsClosedBeforeDocumentAccess() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.read("ws_1", "user_2", "doc_1"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(documentRepository, mongoDocumentEditStore);
    }
}
