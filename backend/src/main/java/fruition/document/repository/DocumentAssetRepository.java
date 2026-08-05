package fruition.document.repository;

import fruition.document.domain.DocumentAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentAssetRepository extends JpaRepository<DocumentAsset, UUID> {
    Optional<DocumentAsset> findByIdAndWorkspaceId(UUID id, String workspaceId);
    List<DocumentAsset> findAllByIdInAndWorkspaceId(Iterable<UUID> ids, String workspaceId);
    List<DocumentAsset> findTop100ByUnreferencedSinceLessThanEqualOrderByUnreferencedSinceAsc(Instant threshold);
}
