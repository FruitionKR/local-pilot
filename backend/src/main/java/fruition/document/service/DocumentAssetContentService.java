package fruition.document.service;

import fruition.document.dto.DocumentAttachmentSaveResponse;
import fruition.document.dto.DocumentContentSaveResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentAssetContentService {

    private final DocumentAssetSaveRequestParser requestParser;
    private final DocumentAssetValidator assetValidator;
    private final DocumentAssetStorageCoordinator storageCoordinator;
    private final DocumentService documentService;

    public DocumentAssetContentService(
            DocumentAssetSaveRequestParser requestParser,
            DocumentAssetValidator assetValidator,
            DocumentAssetStorageCoordinator storageCoordinator,
            DocumentService documentService
    ) {
        this.requestParser = requestParser;
        this.assetValidator = assetValidator;
        this.storageCoordinator = storageCoordinator;
        this.documentService = documentService;
    }

    public DocumentContentSaveResponse save(
            String workspaceId,
            String userId,
            String documentId,
            String metadataJson,
            MultiValueMap<String, MultipartFile> fileParts,
            String applyOperationId
    ) {
        var request = requestParser.parse(metadataJson, fileParts);
        documentService.validateContentSave(
                workspaceId, userId, documentId, request.baseVersion(), applyOperationId);

        Map<UUID, DocumentAssetValidator.ValidatedAsset> validated =
                assetValidator.validateAll(request.attachments());

        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored =
                storageCoordinator.storeAll(workspaceId, validated);
        String finalMarkdown = replacePlaceholders(request.markdown(), workspaceId, stored);
        try {
            DocumentContentSaveResponse saved = documentService.saveContentWithAssets(
                    workspaceId, userId, documentId, finalMarkdown, request.baseVersion(), stored,
                    applyOperationId);
            if (!saved.changed()) storageCoordinator.compensate(stored.values());
            return new DocumentContentSaveResponse(
                    saved.documentId(), saved.currentVersion(), saved.contentHash(), saved.updatedAt(),
                    saved.changed(), saved.markdown(), attachmentResponses(workspaceId, stored));
        } catch (RuntimeException exception) {
            storageCoordinator.compensate(stored.values());
            throw exception;
        }
    }

    private String replacePlaceholders(
            String markdown,
            String workspaceId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored
    ) {
        String replaced = markdown;
        for (Map.Entry<UUID, DocumentAssetStorageCoordinator.StoredAsset> entry : stored.entrySet()) {
            replaced = replaced.replace(
                    "attachment://" + entry.getKey(),
                    contentPath(workspaceId, entry.getValue().assetId()));
        }
        return replaced;
    }

    private List<DocumentAttachmentSaveResponse> attachmentResponses(
            String workspaceId,
            Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored
    ) {
        return stored.entrySet().stream()
                .map(entry -> new DocumentAttachmentSaveResponse(
                        entry.getKey(), entry.getValue().assetId(),
                        contentPath(workspaceId, entry.getValue().assetId())))
                .toList();
    }

    private String contentPath(String workspaceId, UUID assetId) {
        return "/api/workspaces/" + workspaceId + "/assets/" + assetId + "/content";
    }
}
