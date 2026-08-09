package fruition.core.query.service;

import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.query.domain.QueryRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRunServiceTest {

    @Mock QueryRunStore queryRunStore;
    @Mock QueryService queryService;
    @Mock AiCommandOutboxWriter outboxWriter;

    private QueryRunService service;

    @BeforeEach
    void setUp() {
        service = new QueryRunService(queryRunStore, queryService, outboxWriter, "ai.query.command");
    }

    @Test
    void start_createsPendingMessagesAndCommand() {
        QueryRun pending = QueryRun.pending("query_abc123", "ws_abc123", "session_abc123",
                "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("ws_abc123", "session_abc123", "질문")).thenReturn(pending);
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123"))
                .thenReturn(new QueryService.QueryMessageContext(
                        "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", pending.createdAt()));

        QueryRun returned = service.start("ws_abc123", "user_abc123", "session_abc123", "질문");

        assertThat(returned).isEqualTo(pending);
        verify(outboxWriter).enqueue(org.mockito.ArgumentMatchers.eq("query_abc123"),
                org.mockito.ArgumentMatchers.eq("ai.query.command"),
                org.mockito.ArgumentMatchers.eq("session_abc123"), any());
    }

}
