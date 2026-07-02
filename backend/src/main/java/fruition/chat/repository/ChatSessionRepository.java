package fruition.chat.repository;

import fruition.chat.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findAllByWorkspaceIdOrderByLastMessageAtDesc(String workspaceId);

    Optional<ChatSession> findByIdAndWorkspaceId(String id, String workspaceId);

    long countByWorkspaceId(String workspaceId);
}
