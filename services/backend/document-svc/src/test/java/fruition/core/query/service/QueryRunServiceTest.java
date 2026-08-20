package fruition.core.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.document.domain.AiCommandOutbox;
import fruition.core.document.repository.AiCommandOutboxRepository;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.chat.service.ChatSessionService;
import fruition.core.query.domain.QueryRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRunServiceTest {

    @Mock QueryRunStore queryRunStore;
    @Mock QueryService queryService;
    @Mock ChatSessionService chatSessionService;
    @Mock AiCommandOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private QueryRunService service;
    private AiCommandOutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        outboxWriter = spy(new AiCommandOutboxWriter(outboxRepository, objectMapper));
        service = new QueryRunService(queryRunStore, queryService, chatSessionService, outboxWriter,
                "ai.query.command");
    }

    @Test
    void start_createsPendingMessagesAndCommand() {
        QueryRun pending = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123",
                "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("ws_abc123", "session_abc123", "openai", "gpt-5-nano", "질문"))
                .thenReturn(pending);
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123",
                "openai", "gpt-5-nano"))
                .thenReturn(new QueryService.QueryMessageContext(
                        "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", pending.createdAt(),
                        List.of(new fruition.core.query.repository.PipelineQueryRequester.RecentMessage("user", "이전 질문"),
                                new fruition.core.query.repository.PipelineQueryRequester.RecentMessage("assistant", "이전 답변"))));

        QueryRun returned = service.start("ws_abc123", "user_abc123", "session_abc123", "질문");

        assertThat(returned).isEqualTo(pending);
        ArgumentCaptor<QueryRunService.QueryCommand> command =
                ArgumentCaptor.forClass(QueryRunService.QueryCommand.class);
        verify(outboxWriter).enqueue(eq("query_abc123"), eq("ai.query.command"),
                eq("session_abc123"), command.capture());
        ArgumentCaptor<AiCommandOutbox> outbox = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(command.getValue().recentMessages()).extracting(
                fruition.core.query.repository.PipelineQueryRequester.RecentMessage::content)
                .containsExactly("이전 질문", "이전 답변");
        assertThat(outbox.getValue().getPayload())
                .contains("\"provider\":\"openai\"", "\"model\":\"gpt-5-nano\"",
                        "\"recent_messages\":[{\"role\":\"user\",\"content\":\"이전 질문\"},"
                                + "{\"role\":\"assistant\",\"content\":\"이전 답변\"}]",
                        "\"allow_web_search\":false");
    }

    /** 세션에 쌓인 요약을 함께 보내야 pipeline이 이번 턴 요약을 앞 내용에 이어 쓴다. */
    @Test
    void start_sendsAccumulatedConversationSummary() {
        QueryRun pending = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123",
                "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("ws_abc123", "session_abc123", "openai", "gpt-5-nano", "질문"))
                .thenReturn(pending);
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123",
                "openai", "gpt-5-nano"))
                .thenReturn(new QueryService.QueryMessageContext(
                        "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", pending.createdAt(),
                        List.of()));
        when(chatSessionService.contextSummary("session_abc123")).thenReturn("지금까지 인덱싱을 다뤘다.");

        service.start("ws_abc123", "user_abc123", "session_abc123", "질문");

        ArgumentCaptor<AiCommandOutbox> outbox = ArgumentCaptor.forClass(AiCommandOutbox.class);
        verify(outboxRepository).save(outbox.capture());
        assertThat(outbox.getValue().getPayload())
                .contains("\"recent_conversation_summary\":\"지금까지 인덱싱을 다뤘다.\"");
    }

}
