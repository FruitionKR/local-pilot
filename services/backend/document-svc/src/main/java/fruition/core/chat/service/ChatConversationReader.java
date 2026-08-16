package fruition.core.chat.service;

import fruition.core.chat.domain.ChatMessage;
import fruition.core.chat.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI에게 넘길 대화 맥락을 세션에서 읽어 조립한다.
 *
 * <p>어떤 문답을 쓸지는 사용자가 화면에서 고르지만, 어떤 형식으로 pipeline에 넘길지는 서버가 정한다.
 * 클라이언트가 만든 문자열을 그대로 실어 보내면 내용이 실제 대화와 다를 수 있고 형식도 갈린다.
 *
 * <p>선택한 pair는 항상 세션 안에서만 찾는다. 남의 세션 pair ID를 넣어도 조회되지 않는다.
 */
@Service
public class ChatConversationReader {

    /** pipeline이 받는 상한과 같다. 더 보내도 잘린다. */
    private static final int MAX_RECENT_MESSAGES = 6;
    /** 요약 상한. 넘치면 오래된 앞쪽을 버려 최근 맥락을 남긴다. */
    private static final int MAX_SUMMARY_CHARS = 4000;
    private static final int MAX_MESSAGE_CONTENT_LENGTH = 2000;
    private static final Map<String, String> ROLE_LABEL = Map.of("user", "사용자", "assistant", "어시스턴트");

    private final ChatMessageRepository chatMessageRepository;

    public ChatConversationReader(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * @param selectedPairIds 사용자가 고른 문답. 비어 있으면 세션의 최근 완결 문답을 쓴다.
     */
    @Transactional(readOnly = true)
    public Conversation read(String sessionId, List<String> selectedPairIds) {
        List<ChatMessage> messages = chatMessageRepository.findAllBySession_IdOrderByCreatedAtAsc(sessionId);
        Set<String> usablePairIds = completePairIds(messages);
        if (selectedPairIds != null && !selectedPairIds.isEmpty()) {
            // 세션에서 읽은 쌍과 교집합만 남긴다. 다른 세션 ID는 여기서 사라진다.
            usablePairIds = usablePairIds.stream()
                    .filter(selectedPairIds::contains)
                    .collect(Collectors.toSet());
        }

        Set<String> included = usablePairIds;
        List<ChatMessage> ordered = messages.stream()
                .filter(message -> included.contains(message.getPairId()))
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt)
                        .thenComparing(ChatMessage::getPairId)
                        .thenComparingInt(message -> "user".equals(message.getRole()) ? 0 : 1))
                .toList();
        List<ChatMessage> recent = ordered.subList(Math.max(0, ordered.size() - MAX_RECENT_MESSAGES), ordered.size());

        return new Conversation(
                recent.stream().map(message -> new Message(message.getRole(), truncate(message.getContent()))).toList(),
                summarize(ordered));
    }

    /** user·assistant가 하나씩이고 둘 다 완료된 문답만 맥락으로 쓴다. */
    private static Set<String> completePairIds(List<ChatMessage> messages) {
        return messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::getPairId))
                .entrySet().stream()
                .filter(pair -> pair.getValue().size() == 2)
                .filter(pair -> pair.getValue().stream().allMatch(message -> "completed".equals(message.getStatus())))
                .filter(pair -> pair.getValue().stream().filter(message -> "user".equals(message.getRole())).count() == 1)
                .filter(pair -> pair.getValue().stream().filter(message -> "assistant".equals(message.getRole())).count() == 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static String summarize(List<ChatMessage> ordered) {
        if (ordered.isEmpty()) {
            return null;
        }
        String summary = ordered.stream()
                .map(message -> ROLE_LABEL.getOrDefault(message.getRole(), message.getRole())
                        + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        return summary.length() <= MAX_SUMMARY_CHARS
                ? summary
                : summary.substring(summary.length() - MAX_SUMMARY_CHARS);
    }

    private static String truncate(String content) {
        return content.length() <= MAX_MESSAGE_CONTENT_LENGTH
                ? content
                : content.substring(0, MAX_MESSAGE_CONTENT_LENGTH);
    }

    public record Conversation(List<Message> recentMessages, String summary) {}

    public record Message(String role, String content) {}
}
