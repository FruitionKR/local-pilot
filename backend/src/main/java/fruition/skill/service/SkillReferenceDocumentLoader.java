package fruition.skill.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentRole;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceBlockRepository;
import fruition.document.service.DocumentEditStateInitializer;
import fruition.skill.exception.SkillReferenceDocumentNotFoundException;
import fruition.skill.exception.SkillReferenceDocumentTooLargeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

@Component
public class SkillReferenceDocumentLoader {

    private static final int MAX_DOCUMENT_CHARACTERS = 30000;
    private static final int MAX_TOTAL_CHARACTERS = 60000;

    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final SourceBlockRepository sourceBlockRepository;
    private final DocumentEditStateInitializer editStateInitializer;

    public SkillReferenceDocumentLoader(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            SourceBlockRepository sourceBlockRepository,
            DocumentEditStateInitializer editStateInitializer
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.sourceBlockRepository = sourceBlockRepository;
        this.editStateInitializer = editStateInitializer;
    }

    @Transactional
    public List<SkillReferenceDocument> load(String workspaceId, List<String> documentIds) {
        if (new HashSet<>(documentIds).size() != documentIds.size()) {
            throw new SkillReferenceDocumentNotFoundException();
        }
        List<SkillReferenceDocument> references = documentIds.stream()
                .map(documentId -> loadOne(workspaceId, documentId))
                .toList();
        int totalCharacters = references.stream().mapToInt(reference -> reference.content().length()).sum();
        if (totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new SkillReferenceDocumentTooLargeException();
        }
        return references;
    }

    private SkillReferenceDocument loadOne(String workspaceId, String documentId) {
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(SkillReferenceDocumentNotFoundException::new);
        String content = content(document);
        if (content.isBlank()) {
            throw new SkillReferenceDocumentNotFoundException();
        }
        if (content.length() > MAX_DOCUMENT_CHARACTERS) {
            throw new SkillReferenceDocumentTooLargeException();
        }
        return new SkillReferenceDocument(
                document.getId(), document.getDisplayName(), document.getCurrentContentHash(), content);
    }

    private String content(Document document) {
        if (document.getDocumentRole() == DocumentRole.EDITABLE) {
            editStateInitializer.initializeIfNeeded(document);
            return editStateRepository.findById(document.getId())
                    .map(state -> state.getMarkdown())
                    .orElseThrow(SkillReferenceDocumentNotFoundException::new);
        }
        return sourceBlockRepository.findAllByIdDocumentIdOrderByIdBlockIdAsc(document.getId()).stream()
                .map(block -> block.getText())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow(SkillReferenceDocumentNotFoundException::new);
    }
}
