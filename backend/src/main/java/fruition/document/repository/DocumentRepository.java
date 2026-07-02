package fruition.document.repository;

import fruition.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByContentHash(String contentHash);

    List<Document> findAllByWorkspaceId(String workspaceId);

    Optional<Document> findByIdAndWorkspaceId(String id, String workspaceId);
}
