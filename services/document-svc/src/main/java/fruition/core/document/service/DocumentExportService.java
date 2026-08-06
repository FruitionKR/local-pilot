package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class DocumentExportService {

    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;

    public DocumentExportService(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            WorkspaceAccessGuard workspaceAccessGuard
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    @Transactional(readOnly = true)
    public DocumentExportResult exportMarkdown(
            String workspaceId,
            String userId,
            String documentId
    ) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
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
