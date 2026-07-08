package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 채팅 세션을 llmPipeline이 처리할 수 있는 Markdown 원문으로 직렬화한다.
 * 형식은 docs/spec/chat-to-wiki-contract.md §4를 따른다.
 *
 * 문답(pair) 1쌍을 하나의 단위로 만들고, 각 문답 앞에 원본을 특정하는 {@code [session_id:pair_id]} prefix를 붙인다.
 */
@Component
public class ChatWikiMarkdownSerializer {

    /** completed user+assistant가 모두 있는 문답만 대화 순서대로 직렬화한다. messages는 created_at 오름차순 가정. */
    public String serialize(ChatSession session, List<ChatMessage> messages) {
        // pair_id별로 user/assistant 발화를 모은다 (등장 순서 보존).
        Map<String, String[]> pairs = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (!"completed".equals(message.getStatus())) {
                continue;
            }
            String[] qa = pairs.computeIfAbsent(message.getPairId(), k -> new String[2]);
            if ("user".equals(message.getRole())) {
                qa[0] = message.getContent();
            } else if ("assistant".equals(message.getRole())) {
                qa[1] = message.getContent();
            }
        }

        StringBuilder sb = new StringBuilder("# Chat Export\n\n");
        for (Map.Entry<String, String[]> entry : pairs.entrySet()) {
            String user = entry.getValue()[0];
            String assistant = entry.getValue()[1];
            if (user == null || assistant == null) {
                continue; // 불완전한 문답은 제외 (§4: user·assistant 모두 포함)
            }
            sb.append('[').append(session.getId()).append(':').append(entry.getKey()).append("]Q : ")
              .append(collapseBlankLines(user)).append('\n');
            sb.append("A : ").append(collapseBlankLines(assistant)).append("\n\n");
        }
        return sb.toString();
    }

    /** §4: 문답 1쌍 안에는 빈 줄을 넣지 않는다. 발화 내부의 빈 줄을 단일 개행으로 접는다. */
    private String collapseBlankLines(String content) {
        return content.strip().replaceAll("\\n\\s*\\n+", "\n");
    }
}
