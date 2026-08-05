package fruition.document.repository;

import fruition.document.domain.DocumentAssetReference;
import fruition.document.domain.DocumentAssetReferenceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentAssetReferenceRepository
        extends JpaRepository<DocumentAssetReference, DocumentAssetReferenceId> {
    List<DocumentAssetReference> findAllByIdDocumentId(String documentId);
    boolean existsByIdAssetId(UUID assetId);
}
