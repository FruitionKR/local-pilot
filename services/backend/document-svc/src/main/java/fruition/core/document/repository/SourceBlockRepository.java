package fruition.core.document.repository;

import fruition.core.document.domain.SourceBlock;
import fruition.core.document.domain.SourceBlockId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceBlockRepository extends JpaRepository<SourceBlock, SourceBlockId> {
    List<SourceBlock> findAllByIdDocumentIdOrderByIdBlockIdAsc(String documentId);

    void deleteByIdDocumentId(String documentId);
}
