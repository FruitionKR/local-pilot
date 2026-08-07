package fruition.core.query.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.domain.ChatSession;
import fruition.core.chat.exception.ChatSessionNotFoundException;
import fruition.core.chat.repository.ChatMessageRepository;
import fruition.core.chat.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class QueryMessageRecorder {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionRepository chatSessionRepository;

    public QueryMessageRecorder(ChatMessageRepository chatMessageRepository,
                                ChatSessionRepository chatSessionRepository) {
        this.chatMessageRepository = chatMessageRepository;
        this.chatSessionRepository = chatSessionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createPendingPair(String sessionId,
                                  String pairId,
                                  String userMessageId,
                                  String assistantMessageId,
                                  String question,
                                  Instant createdAt) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
        chatMessageRepository.saveAll(List.of(
                new ChatMessage(userMessageId, session, pairId, "user", question, "completed", createdAt, null),
                new ChatMessage(assistantMessageId, session, pairId, "assistant", "", "pending", createdAt, null)
        ));
        session.touchLastMessageAt(createdAt);
        chatSessionRepository.save(session);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String assistantMessageId, String errorMessage) {
        ChatMessage assistantMessage = chatMessageRepository.findById(assistantMessageId)
                .orElseThrow(() -> new IllegalStateException("처리 중인 assistant 메시지를 찾을 수 없습니다: " + assistantMessageId));
        assistantMessage.fail(errorMessage);
        chatMessageRepository.save(assistantMessage);
    }
}
