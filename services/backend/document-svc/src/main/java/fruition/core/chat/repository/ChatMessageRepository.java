package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findAllBySession_IdOrderByCreatedAtAsc(String sessionId);

    /** 완료 마킹 대상: 세션의 특정 pair 문답 메시지들. */
    List<ChatMessage> findAllBySession_IdAndPairIdIn(String sessionId, Collection<String> pairIds);
}
