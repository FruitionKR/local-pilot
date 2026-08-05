package fruition.document.service;

import fruition.document.domain.DocumentAsset;
import fruition.document.domain.DocumentAssetOrphan;
import fruition.document.exception.DocumentAssetStorageException;
import fruition.document.repository.DocumentAssetOrphanRepository;
import fruition.document.repository.DocumentAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetCleanupWorkerTest {

    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetOrphanRepository orphanRepository;
    @Mock DocumentAssetObjectStorage objectStorage;

    @Test
    void cleanup_deletesObjectBeforeAssetRow() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        DocumentAsset asset = asset("asset-key");
        when(assetRepository.lockCleanupCandidates(now.minus(Duration.ofDays(7))))
                .thenReturn(List.of(asset));
        when(orphanRepository.lockRetryCandidates(now.minus(Duration.ofDays(7)))).thenReturn(List.of());

        worker().cleanup(now);

        var order = org.mockito.Mockito.inOrder(objectStorage, assetRepository);
        order.verify(objectStorage).delete("asset-key");
        order.verify(assetRepository).delete(asset);
    }

    @Test
    void cleanup_storageFailureKeepsAssetRowForRetry() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        DocumentAsset asset = asset("asset-key");
        when(assetRepository.lockCleanupCandidates(now.minus(Duration.ofDays(7))))
                .thenReturn(List.of(asset));
        when(orphanRepository.lockRetryCandidates(now.minus(Duration.ofDays(7)))).thenReturn(List.of());
        doThrow(new DocumentAssetStorageException("실패", new IllegalStateException()))
                .when(objectStorage).delete("asset-key");

        worker().cleanup(now);

        verify(assetRepository, never()).delete(asset);
    }

    @Test
    void cleanup_retriesOrphanAndRecordsFailure() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        DocumentAssetOrphan orphan = new DocumentAssetOrphan(
                UUID.randomUUID(), "orphan-key", now.minusSeconds(60), "첫 실패");
        when(assetRepository.lockCleanupCandidates(now.minus(Duration.ofDays(7))))
                .thenReturn(List.of());
        when(orphanRepository.lockRetryCandidates(now.minus(Duration.ofDays(7))))
                .thenReturn(List.of(orphan));
        doThrow(new DocumentAssetStorageException("재시도 실패", new IllegalStateException()))
                .when(objectStorage).delete("orphan-key");

        worker().cleanup(now);

        assertThat(orphan.getRetryCount()).isEqualTo(1);
        assertThat(orphan.getFailedAt()).isEqualTo(now);
        verify(orphanRepository, never()).delete(orphan);
    }

    private DocumentAssetCleanupWorker worker() {
        return new DocumentAssetCleanupWorker(assetRepository, orphanRepository, objectStorage);
    }

    private DocumentAsset asset(String storageKey) {
        return new DocumentAsset(UUID.randomUUID(), "ws_1", "user_1", "image.png", "image/png",
                3, 1, 1, "a".repeat(64), storageKey, Instant.now());
    }
}
