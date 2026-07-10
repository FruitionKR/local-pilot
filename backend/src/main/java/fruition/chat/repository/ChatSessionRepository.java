package fruition.chat.repository;

import fruition.chat.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findAllByWorkspaceIdOrderByLastMessageAtDesc(String workspaceId);

    Optional<ChatSession> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<ChatSession> findByWikiExportDocumentId(String wikiExportDocumentId);

    /** export 문서는 지정됐지만 아직 source wiki page가 연결되지 않은 세션 (완료 감지 reconcile 대상). */
    List<ChatSession> findByWikiExportDocumentIdIsNotNullAndWikiPageIdIsNull();

    long countByWorkspaceId(String workspaceId);
}
