package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentEditState;
import fruition.document.domain.DocumentRole;
import fruition.document.dto.DocumentExportResult;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.repository.DocumentRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class DocumentExportService {

    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public DocumentExportService(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            WorkspaceMemberRepository workspaceMemberRepository
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional(readOnly = true)
    public DocumentExportResult exportMarkdown(
            String workspaceId,
            String userId,
            String documentId
    ) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        Document document = documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentNotFoundException(documentId);
        }
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        return new DocumentExportResult(
                document.getDisplayName() + ".md",
                editState.getMarkdown().getBytes(StandardCharsets.UTF_8)
        );
    }
}
