package fruition.core.chat.repository;

import fruition.TestcontainersConfiguration;
import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 문답의 질문과 답변은 같은 시각으로 저장된다. created_at만으로 정렬하면 동점이라 순서가
 * 정해지지 않고, 실제로 화면에 답변이 질문 위로 뜬 적이 있다. Mockito로는 이 순서를 확인할 수
 * 없어 실제 Postgres에서 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ChatMessageTurnOrderIntegrationTest {

    @Autowired ChatSessionRepository chatSessionRepository;
    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired EntityManager entityManager;

    @Test
    void sameInstantPairKeepsQuestionBeforeAnswer() {
        ChatSession session = new ChatSession("session_turn_order", "ws_turn_order", "user_turn_order", null);
        chatSessionRepository.save(session);

        Instant first = Instant.parse("2026-08-16T00:00:00Z");
        Instant second = first.plusSeconds(60);
        // 답변을 먼저 저장해 삽입 순서가 정렬을 대신 결정하지 못하게 한다.
        chatMessageRepository.saveAll(List.of(
                new ChatMessage("chat_assistant_p2", session, "pair_2", "assistant", "둘째 답변",
                        "completed", second, null),
                new ChatMessage("chat_user_p2", session, "pair_2", "user", "둘째 질문",
                        "completed", second, null),
                new ChatMessage("chat_assistant_p1", session, "pair_1", "assistant", "첫 답변",
                        "completed", first, null),
                new ChatMessage("chat_user_p1", session, "pair_1", "user", "첫 질문",
                        "completed", first, null)));
        entityManager.flush();
        entityManager.clear();

        List<ChatMessage> messages = chatMessageRepository.findAllBySessionIdInTurnOrder("session_turn_order");

        assertThat(messages).extracting(ChatMessage::getContent)
                .containsExactly("첫 질문", "첫 답변", "둘째 질문", "둘째 답변");
    }
}
