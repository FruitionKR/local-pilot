package fruition.core.document.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentAssetStorageCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssetStorageCoordinator.class);
    private final DocumentAssetObjectStorage objectStorage;
    private final DocumentAssetOrphanRegistry orphanRegistry;

    public DocumentAssetStorageCoordinator(
            DocumentAssetObjectStorage objectStorage,
            DocumentAssetOrphanRegistry orphanRegistry
    ) {
        this.objectStorage = objectStorage;
        this.orphanRegistry = orphanRegistry;
    }

    public Map<UUID, StoredAsset> storeAll(
            String workspaceId,
            Map<UUID, DocumentAssetValidator.ValidatedAsset> attachments
    ) {
        Map<UUID, StoredAsset> stored = new LinkedHashMap<>();
        try {
            attachments.forEach((attachmentId, asset) -> {
                UUID assetId = UUID.randomUUID();
                String objectKey = objectKey(workspaceId, assetId);
                objectStorage.put(objectKey, asset.contentType(), asset.bytes());
                stored.put(attachmentId, new StoredAsset(assetId, objectKey, asset));
            });
            return Map.copyOf(stored);
        } catch (RuntimeException exception) {
            compensate(stored.values());
            throw exception;
        }
    }

    public void compensate(Collection<StoredAsset> storedAssets) {
        List<String> failedAssetIds = new ArrayList<>();
        for (StoredAsset storedAsset : storedAssets) {
            try {
                objectStorage.delete(storedAsset.objectKey());
            } catch (RuntimeException exception) {
                failedAssetIds.add(storedAsset.assetId().toString());
                try {
                    orphanRegistry.record(storedAsset.assetId(), storedAsset.objectKey(), exception.getMessage());
                } catch (RuntimeException registryException) {
                    log.error("[이미지 asset orphan 기록 실패] assetId={}", storedAsset.assetId());
                }
            }
        }
        if (!failedAssetIds.isEmpty()) {
            log.error("[이미지 asset 보상 삭제 실패] assetIds={}", failedAssetIds);
        }
    }

    private String objectKey(String workspaceId, UUID assetId) {
        return "assets/" + workspaceId + "/" + assetId + "/content";
    }

    public record StoredAsset(
            UUID assetId,
            String objectKey,
            DocumentAssetValidator.ValidatedAsset validated
    ) {
    }
}
