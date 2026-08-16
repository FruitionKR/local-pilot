package fruition.core.chat.repository;

import fruition.core.chat.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    /**
     * 세션의 메시지를 대화 순서대로 읽는다.
     *
     * <p>한 문답의 질문과 답변은 같은 시각으로 저장된다. created_at만으로 정렬하면 동점이라
     * 순서가 정해지지 않아 답변이 질문 위에 뜬다. 문답 안에서는 질문이 먼저라고 못박는다.
     */
    @Query("""
            SELECT m FROM ChatMessage m
            WHERE m.session.id = :sessionId
            ORDER BY m.createdAt ASC, m.pairId ASC,
                     CASE WHEN m.role = 'user' THEN 0 ELSE 1 END ASC
            """)
    List<ChatMessage> findAllBySessionIdInTurnOrder(@Param("sessionId") String sessionId);

    /** 완료 마킹 대상: 세션의 특정 pair 문답 메시지들. */
    List<ChatMessage> findAllBySession_IdAndPairIdIn(String sessionId, Collection<String> pairIds);
}
