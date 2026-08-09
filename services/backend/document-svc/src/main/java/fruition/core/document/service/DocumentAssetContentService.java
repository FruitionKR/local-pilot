package fruition.core.document.service;

import fruition.core.document.dto.DocumentAttachmentSaveResponse;
import fruition.core.document.dto.DocumentContentSaveResponse;
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
        documentService.validateContentSavePreconditions(workspaceId, userId, documentId);

        Map<UUID, DocumentAssetValidator.ValidatedAsset> validated =
                assetValidator.validateAll(request.attachments());

        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored =
                storageCoordinator.storeAll(workspaceId, documentId, request.baseVersion(), validated);
        String finalMarkdown = replacePlaceholders(request.markdown(), workspaceId, stored);
        // write ID도 asset ID도 요청 내용에서 결정된다. 같은 요청을 재전송하면 본문까지 동일해져
        // 저장 계층이 첫 결과를 그대로 돌려준다.
        String revisionWriteId = "assets:" + documentId + ":" + request.baseVersion()
                + ":" + DocumentEditingRules.markdown(request.markdown()).contentHash();
        try {
            DocumentContentSaveResponse saved = documentService.saveContentWithAssets(
                    workspaceId, userId, documentId, finalMarkdown, request.baseVersion(),
                    revisionWriteId, stored, applyOperationId);
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
