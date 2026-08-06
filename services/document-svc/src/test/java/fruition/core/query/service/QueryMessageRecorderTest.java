package fruition.core.query.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryMessageRecorderTest {

    @Mock ChatMessageRepository chatMessageRepository;
    @Mock ChatSessionRepository chatSessionRepository;

    private QueryMessageRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new QueryMessageRecorder(chatMessageRepository, chatSessionRepository);
    }

    @Test
    void createPendingPair_savesCompletedUserAndPendingAssistant() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        when(chatSessionRepository.findById("session_abc123")).thenReturn(Optional.of(session));
        Instant createdAt = Instant.parse("2026-07-21T01:00:00Z");

        recorder.createPendingPair(
                "session_abc123", "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", "질문", createdAt);

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageRepository).saveAll(captor.capture());
        verify(chatSessionRepository).save(session);

        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getRole()).isEqualTo("user");
        assertThat(captor.getValue().get(0).getStatus()).isEqualTo("completed");
        assertThat(captor.getValue().get(0).getContent()).isEqualTo("질문");
        assertThat(captor.getValue().get(1).getRole()).isEqualTo("assistant");
        assertThat(captor.getValue().get(1).getStatus()).isEqualTo("pending");
        assertThat(captor.getValue().get(1).getContent()).isEmpty();
        assertThat(session.getLastMessageAt()).isEqualTo(createdAt);
    }

    @Test
    void markFailed_updatesExistingAssistant() {
        ChatSession session = new ChatSession("session_abc123", "ws_abc123", "user_abc123", null);
        ChatMessage assistant = new ChatMessage(
                "chat_assistant_abc123", session, "pair_abc123", "assistant", "", "pending", Instant.now(), null);
        when(chatMessageRepository.findById("chat_assistant_abc123")).thenReturn(Optional.of(assistant));

        recorder.markFailed("chat_assistant_abc123", "pipeline 실패");

        assertThat(assistant.getStatus()).isEqualTo("failed");
        assertThat(assistant.getErrorMessage()).isEqualTo("pipeline 실패");
        verify(chatMessageRepository).save(assistant);
    }

    @Test
    void methods_useRequiresNewTransaction() throws NoSuchMethodException {
        Transactional createTransaction = QueryMessageRecorder.class
                .getMethod("createPendingPair", String.class, String.class, String.class, String.class,
                        String.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional failTransaction = QueryMessageRecorder.class
                .getMethod("markFailed", String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(createTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(failTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
