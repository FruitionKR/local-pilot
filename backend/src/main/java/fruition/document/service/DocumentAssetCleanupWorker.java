package fruition.document.service;

import fruition.document.exception.DocumentAssetStorageException;
import fruition.document.repository.DocumentAssetOrphanRepository;
import fruition.document.repository.DocumentAssetRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class DocumentAssetCleanupWorker {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetOrphanRepository orphanRepository;
    private final DocumentAssetObjectStorage objectStorage;

    public DocumentAssetCleanupWorker(
            DocumentAssetRepository assetRepository,
            DocumentAssetOrphanRepository orphanRepository,
            DocumentAssetObjectStorage objectStorage
    ) {
        this.assetRepository = assetRepository;
        this.orphanRepository = orphanRepository;
        this.objectStorage = objectStorage;
    }

    @Scheduled(fixedDelayString = "${app.document-assets.cleanup-delay-ms:86400000}")
    @Transactional
    public void runScheduled() {
        cleanup(Instant.now());
    }

    @Transactional
    public void cleanup(Instant now) {
        for (var asset : assetRepository.lockCleanupCandidates(now.minus(RETENTION))) {
            try {
                objectStorage.delete(asset.getStorageKey());
                assetRepository.delete(asset);
            } catch (DocumentAssetStorageException ignored) {
                // DB row를 유지해 다음 worker 실행에서 재시도한다.
            }
        }
        for (var orphan : orphanRepository.lockRetryCandidates(now.minus(RETENTION))) {
            try {
                objectStorage.delete(orphan.getStorageKey());
                orphanRepository.delete(orphan);
            } catch (DocumentAssetStorageException exception) {
                orphan.recordRetryFailure(now, exception.getMessage());
            }
        }
    }
}
