package fruition.document.repository;

import fruition.document.domain.DocumentAssetOrphan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAssetOrphanRepository extends JpaRepository<DocumentAssetOrphan, UUID> {
    Optional<DocumentAssetOrphan> findByStorageKey(String storageKey);
    List<DocumentAssetOrphan> findTop100ByOrderByFailedAtAsc();

    @Query(value = """
            SELECT * FROM document_asset_orphans
            WHERE failed_at <= :threshold
            ORDER BY failed_at
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DocumentAssetOrphan> lockRetryCandidates(Instant threshold);
}
