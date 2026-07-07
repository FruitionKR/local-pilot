package fruition.chat.service;

import fruition.chat.domain.ChatMessage;
import fruition.chat.domain.ChatSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatWikiMarkdownSerializerTest {

    private final ChatWikiMarkdownSerializer serializer = new ChatWikiMarkdownSerializer();

    private static final Instant EXPORTED_AT = Instant.parse("2026-07-07T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-07T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-07T10:00:01Z");
    private static final Instant T3 = Instant.parse("2026-07-07T10:01:00Z");

    private ChatMessage message(ChatSession session, String id, String role, String content, String status, Instant createdAt) {
        return new ChatMessage(id, session, "pair_1", role, content, status, createdAt, null);
    }

    @Test
    @DisplayName("§6 구조로 completed 메시지를 시간순으로 직렬화한다")
    void serializesCompletedMessagesInOrder() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "LangSmith 설정 논의");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "user", "LangSmith 연결은 어디서 봐?", "completed", T1),
                message(session, "m2", "assistant", "traces 화면에서 확인합니다.", "completed", T2)
        );

        String md = serializer.serialize(session, messages, EXPORTED_AT);

        assertThat(md).contains("# LangSmith 설정 논의");
        assertThat(md).contains("- workspace_id: ws_1");
        assertThat(md).contains("- conversation_id: session_1");
        assertThat(md).contains("- exported_at: 2026-07-07T00:00:00Z");
        assertThat(md).contains("### 2026-07-07T10:00:00Z User");
        assertThat(md).contains("LangSmith 연결은 어디서 봐?");
        assertThat(md).contains("### 2026-07-07T10:00:01Z Assistant");
        // 순서 보존: user 발화가 assistant 발화보다 앞
        assertThat(md.indexOf("LangSmith 연결은")).isLessThan(md.indexOf("traces 화면"));
    }

    @Test
    @DisplayName("completed가 아닌 메시지는 제외한다")
    void skipsNonCompletedMessages() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", "제목");
        List<ChatMessage> messages = List.of(
                message(session, "m1", "user", "질문1", "completed", T1),
                message(session, "m_fail", "user", "실패한질문", "failed", T3)
        );

        String md = serializer.serialize(session, messages, EXPORTED_AT);

        assertThat(md).contains("질문1");
        assertThat(md).doesNotContain("실패한질문");
    }

    @Test
    @DisplayName("title이 비어 있으면 세션 id 기반 제목으로 대체한다")
    void fallsBackToSessionIdTitle() {
        ChatSession session = new ChatSession("session_1", "ws_1", "user_1", null);
        String md = serializer.serialize(session, List.of(), EXPORTED_AT);
        assertThat(md).contains("# 채팅 session_1");
    }
}
