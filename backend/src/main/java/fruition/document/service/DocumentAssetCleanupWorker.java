package fruition.document.service;

import fruition.document.exception.DocumentAssetStorageException;
import fruition.document.repository.DocumentAssetOrphanRepository;
import fruition.document.repository.DocumentAssetRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentAssetCleanupWorker {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetOrphanRepository orphanRepository;
    private final DocumentAssetObjectStorage objectStorage;
    private final TransactionTemplate transactionTemplate;

    public DocumentAssetCleanupWorker(
            DocumentAssetRepository assetRepository,
            DocumentAssetOrphanRepository orphanRepository,
            DocumentAssetObjectStorage objectStorage,
            TransactionTemplate transactionTemplate
    ) {
        this.assetRepository = assetRepository;
        this.orphanRepository = orphanRepository;
        this.objectStorage = objectStorage;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${app.document-assets.cleanup-delay-ms:86400000}")
    public void runScheduled() {
        cleanup(Instant.now());
    }

    /**
     * object storage 삭제를 트랜잭션 밖에서 수행한다. storage가 느리거나 멈춰도 DB 트랜잭션과
     * 커넥션을 붙잡지 않는다. object 삭제와 row 삭제 모두 멱등이라 다음 실행에서 다시 시도해도 안전하다.
     */
    public void cleanup(Instant now) {
        Instant threshold = now.minus(RETENTION);

        for (Candidate candidate : loadUnreferencedAssets(threshold)) {
            try {
                objectStorage.delete(candidate.storageKey());
            } catch (DocumentAssetStorageException ignored) {
                continue; // DB row를 유지해 다음 worker 실행에서 재시도한다.
            }
            inTransaction(() -> assetRepository.deleteById(candidate.id()));
        }

        for (Candidate candidate : loadRetryableOrphans(threshold)) {
            try {
                objectStorage.delete(candidate.storageKey());
            } catch (DocumentAssetStorageException exception) {
                recordOrphanRetryFailure(candidate.id(), now, exception.getMessage());
                continue;
            }
            inTransaction(() -> orphanRepository.deleteById(candidate.id()));
        }
    }

    private List<Candidate> loadUnreferencedAssets(Instant threshold) {
        return transactionTemplate.execute(status ->
                assetRepository
                        .findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(threshold)
                        .stream()
                        .map(asset -> new Candidate(asset.getId(), asset.getStorageKey()))
                        .toList());
    }

    private List<Candidate> loadRetryableOrphans(Instant threshold) {
        return transactionTemplate.execute(status ->
                orphanRepository
                        .findTop100ByFailedAtLessThanEqualOrderByFailedAtAsc(threshold)
                        .stream()
                        .map(orphan -> new Candidate(orphan.getId(), orphan.getStorageKey()))
                        .toList());
    }

    private void recordOrphanRetryFailure(UUID orphanId, Instant now, String message) {
        inTransaction(() -> orphanRepository.findById(orphanId)
                .ifPresent(orphan -> orphan.recordRetryFailure(now, message)));
    }

    private void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    /** storage 삭제는 트랜잭션 밖에서 하므로 entity 대신 식별자와 object key만 들고 나온다. */
    private record Candidate(UUID id, String storageKey) {}
}
