package fruition.document.service;

import fruition.document.exception.DocumentAssetStorageException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentAssetStorageCoordinatorTest {

    @Test
    void storeAll_returnsAttachmentMappingWithoutExposingBytesToKey() {
        RecordingStorage storage = new RecordingStorage(-1);
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans);
        UUID attachmentId = UUID.randomUUID();

        var stored = coordinator.storeAll("ws_1", Map.of(attachmentId, asset("first.png")));

        assertThat(stored).containsOnlyKeys(attachmentId);
        assertThat(stored.get(attachmentId).objectKey()).startsWith("assets/ws_1/").endsWith("/content");
        assertThat(storage.putKeys).containsExactly(stored.get(attachmentId).objectKey());
    }

    @Test
    void storeAll_whenLaterPutFails_deletesPreviouslyStoredObjects() {
        RecordingStorage storage = new RecordingStorage(2);
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans);
        Map<UUID, DocumentAssetValidator.ValidatedAsset> assets = new LinkedHashMap<>();
        assets.put(UUID.randomUUID(), asset("first.png"));
        assets.put(UUID.randomUUID(), asset("second.png"));

        assertThatThrownBy(() -> coordinator.storeAll("ws_1", assets))
                .isInstanceOf(DocumentAssetStorageException.class);
        assertThat(storage.deletedKeys).containsExactly(storage.putKeys.getFirst());
    }

    @Test
    void compensate_attemptsEveryDeleteWhenOneDeleteFails() {
        RecordingStorage storage = new RecordingStorage(-1);
        storage.failDeleteIndex = 1;
        RecordingOrphanRegistry orphans = new RecordingOrphanRegistry();
        DocumentAssetStorageCoordinator coordinator = new DocumentAssetStorageCoordinator(storage, orphans);
        Map<UUID, DocumentAssetStorageCoordinator.StoredAsset> stored = coordinator.storeAll(
                "ws_1",
                Map.of(UUID.randomUUID(), asset("first.png"), UUID.randomUUID(), asset("second.png")));

        coordinator.compensate(stored.values());

        assertThat(storage.deleteAttempts).hasSize(2);
        assertThat(orphans.assetIds).hasSize(1);
    }

    private static final class RecordingOrphanRegistry implements DocumentAssetOrphanRegistry {
        private final List<UUID> assetIds = new ArrayList<>();

        @Override
        public void record(UUID assetId, String storageKey, String errorMessage) {
            assetIds.add(assetId);
        }
    }

    private DocumentAssetValidator.ValidatedAsset asset(String filename) {
        byte[] bytes = new byte[]{1, 2, 3};
        return new DocumentAssetValidator.ValidatedAsset(
                filename, "image/png", bytes, 1, 1, "a".repeat(64));
    }

    private static final class RecordingStorage implements DocumentAssetObjectStorage {
        private final int failPutIndex;
        private int putCount;
        private int deleteCount;
        private int failDeleteIndex = -1;
        private final List<String> putKeys = new ArrayList<>();
        private final List<String> deleteAttempts = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();

        private RecordingStorage(int failPutIndex) { this.failPutIndex = failPutIndex; }

        @Override
        public void put(String objectKey, String contentType, byte[] bytes) {
            putCount += 1;
            if (putCount == failPutIndex) {
                throw new DocumentAssetStorageException("저장 실패", new IllegalStateException());
            }
            putKeys.add(objectKey);
        }

        @Override
        public java.io.InputStream get(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            deleteCount += 1;
            deleteAttempts.add(objectKey);
            if (deleteCount == failDeleteIndex) {
                throw new DocumentAssetStorageException("삭제 실패", new IllegalStateException());
            }
            deletedKeys.add(objectKey);
        }
    }
}
