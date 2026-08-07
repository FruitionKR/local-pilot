package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.mongo.MongoDocumentEditState;
import fruition.core.document.mongo.MongoDocumentEditStore;
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
    private final MongoDocumentEditStore mongoDocumentEditStore;

    public DocumentExportService(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            WorkspaceAccessGuard workspaceAccessGuard,
            MongoDocumentEditStore mongoDocumentEditStore
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.mongoDocumentEditStore = mongoDocumentEditStore;
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
        // 최신 편집본은 Mongo가 canonical이다. 없으면 legacy PG 상태로 대체한다.
        String markdown = mongoDocumentEditStore.findState(documentId)
                .map(MongoDocumentEditState::getMarkdown)
                .orElseGet(() -> editStateRepository.findById(documentId)
                        .map(DocumentEditState::getMarkdown)
                        .orElseThrow(() -> new DocumentNotFoundException(documentId)));

        return new DocumentExportResult(
                document.getDisplayName() + ".md",
                markdown.getBytes(StandardCharsets.UTF_8)
        );
    }
}
