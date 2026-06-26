package fruition.document.repository;

import fruition.document.domain.SourceBlock;
import fruition.document.domain.SourceBlockId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SourceBlockRepository extends JpaRepository<SourceBlock, SourceBlockId> {
    List<SourceBlock> findAllByIdDocumentIdOrderByIdBlockIdAsc(String documentId);
}
