package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 저장된 채팅 세션을 llmPipeline이 일반 문서처럼 처리할 수 있는 Markdown 원문으로 직렬화한다.
 * 형식은 docs/spec/chat-to-wiki-contract.md §6을 따른다.
 */
@Component
public class ChatWikiMarkdownSerializer {

    /** completed 상태의 메시지만 대화 순서대로 직렬화한다. messages는 created_at 오름차순이라고 가정한다. */
    public String serialize(ChatSession session, List<ChatMessage> messages, Instant exportedAt) {
        String title = (session.getTitle() != null && !session.getTitle().isBlank())
                ? session.getTitle()
                : "채팅 " + session.getId();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        sb.append("## 대화 정보\n\n");
        sb.append("- workspace_id: ").append(session.getWorkspaceId()).append("\n");
        sb.append("- conversation_id: ").append(session.getId()).append("\n");
        sb.append("- exported_at: ").append(exportedAt).append("\n\n");

        sb.append("## 대화 내용\n\n");
        for (ChatMessage message : messages) {
            if (!"completed".equals(message.getStatus())) {
                continue;
            }
            sb.append("### ").append(message.getCreatedAt())
              .append(" ").append(displayRole(message.getRole())).append("\n\n");
            sb.append(message.getContent().strip()).append("\n\n");
        }

        return sb.toString();
    }

    private String displayRole(String role) {
        if ("user".equals(role)) return "User";
        if ("assistant".equals(role)) return "Assistant";
        return role;
    }
}
