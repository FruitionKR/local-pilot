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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetCleanupWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(Duration.ofDays(7));

    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetOrphanRepository orphanRepository;
    @Mock DocumentAssetObjectStorage objectStorage;

    @Test
    void cleanup_deletesObjectBeforeAssetRow() {
        DocumentAsset asset = asset("asset-key");
        stubCandidates(List.of(asset), List.of());

        worker().cleanup(NOW);

        var order = org.mockito.Mockito.inOrder(objectStorage, assetRepository);
        order.verify(objectStorage).delete("asset-key");
        order.verify(assetRepository).deleteById(asset.getId());
    }

    @Test
    void cleanup_storageFailureKeepsAssetRowForRetry() {
        DocumentAsset asset = asset("asset-key");
        stubCandidates(List.of(asset), List.of());
        doThrow(new DocumentAssetStorageException("실패", new IllegalStateException()))
                .when(objectStorage).delete("asset-key");

        worker().cleanup(NOW);

        verify(assetRepository, never()).deleteById(asset.getId());
    }

    @Test
    void cleanup_retriesOrphanAndRecordsFailure() {
        DocumentAssetOrphan orphan = orphan();
        stubCandidates(List.of(), List.of(orphan));
        doThrow(new DocumentAssetStorageException("재시도 실패", new IllegalStateException()))
                .when(objectStorage).delete("orphan-key");
        when(orphanRepository.findById(orphan.getId())).thenReturn(Optional.of(orphan));

        worker().cleanup(NOW);

        assertThat(orphan.getRetryCount()).isEqualTo(1);
        assertThat(orphan.getFailedAt()).isEqualTo(NOW);
        verify(orphanRepository, never()).deleteById(orphan.getId());
    }

    @Test
    void cleanup_deletesOrphanRowWhenStorageDeleteSucceeds() {
        DocumentAssetOrphan orphan = orphan();
        stubCandidates(List.of(), List.of(orphan));

        worker().cleanup(NOW);

        verify(objectStorage).delete("orphan-key");
        verify(orphanRepository).deleteById(orphan.getId());
    }

    private void stubCandidates(List<DocumentAsset> assets, List<DocumentAssetOrphan> orphans) {
        when(assetRepository.findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(THRESHOLD))
                .thenReturn(assets);
        when(orphanRepository.findTop100ByFailedAtLessThanEqualOrderByFailedAtAsc(THRESHOLD))
                .thenReturn(orphans);
    }

    private DocumentAssetCleanupWorker worker() {
        return new DocumentAssetCleanupWorker(
                assetRepository, orphanRepository, objectStorage,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private DocumentAsset asset(String storageKey) {
        return new DocumentAsset(UUID.randomUUID(), "ws_1", "user_1", "image.png", "image/png",
                3, 1, 1, "a".repeat(64), storageKey, Instant.now());
    }

    private DocumentAssetOrphan orphan() {
        return new DocumentAssetOrphan(
                UUID.randomUUID(), "orphan-key", NOW.minusSeconds(60), "첫 실패");
    }
}
