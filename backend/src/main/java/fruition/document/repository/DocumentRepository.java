package fruition.document.repository;

import fruition.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    Optional<Document> findByContentHash(String contentHash);

    List<Document> findAllByWorkspaceId(String workspaceId);

    /** 사용자 문서 목록: 채팅 Wiki page화 export(origin=chat_export)는 제외한다. origin이 null인 기존 업로드는 포함. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND (d.origin IS NULL OR d.origin <> 'chat_export')")
    List<Document> findVisibleByWorkspaceId(@Param("workspaceId") String workspaceId);

    Optional<Document> findByIdAndWorkspaceId(String id, String workspaceId);
}
