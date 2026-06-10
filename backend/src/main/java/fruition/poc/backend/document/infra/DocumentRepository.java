package fruition.poc.backend.document.infra;

import fruition.poc.backend.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByContentHash(String contentHash);
}
