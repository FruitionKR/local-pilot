package fruition.core.document.repository;

import fruition.core.document.domain.DocumentProcessingQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentProcessingQueueRepository extends JpaRepository<DocumentProcessingQueue, Long> {

    Optional<DocumentProcessingQueue> findFirstByStatusOrderByCreatedAtAsc(String status);

    List<DocumentProcessingQueue> findAllByStatus(String status);

    void deleteByDocumentId(String documentId);
}
