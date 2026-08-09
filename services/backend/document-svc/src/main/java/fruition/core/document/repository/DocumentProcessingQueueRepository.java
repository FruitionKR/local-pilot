package fruition.core.document.repository;

import fruition.core.document.domain.DocumentProcessingQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface DocumentProcessingQueueRepository extends JpaRepository<DocumentProcessingQueue, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentProcessingQueue> findFirstByStatusOrderByCreatedAtAsc(String status);

    List<DocumentProcessingQueue> findAllByStatus(String status);

    void deleteByDocumentId(String documentId);
}
