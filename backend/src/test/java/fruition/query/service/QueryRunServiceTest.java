package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
import fruition.query.exception.PipelineQueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRunServiceTest {

    @Mock QueryRunStore queryRunStore;
    @Mock QueryEventBroker queryEventBroker;
    @Mock QueryService queryService;

    private QueryRunService queryRunService;

    @BeforeEach
    void setUp() {
        // Executor runs the submitted task synchronously on the calling thread,
        // so assertions below don't need to wait for a background thread.
        queryRunService = new QueryRunService(
                queryRunStore, queryEventBroker, queryService, Runnable::run, "http://backend:8080");
    }

    @Test
    void start_pipelineSucceeds_marksRunCompletedAndBroadcastsCompletion() {
        QueryRun pending = QueryRun.pending("query_abc123", "session_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("session_abc123", "질문")).thenReturn(pending);
        QueryService.QueryMessageContext messageContext = messageContext();
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123")).thenReturn(messageContext);
        QueryResponse result = new QueryResponse(null, null, null, null, null, null);
        when(queryService.query("ws_abc123", "session_abc123", "질문", "query_abc123",
                "http://backend:8080/api/query/runs/query_abc123/events/callback", messageContext))
                .thenReturn(result);

        QueryRun returned = queryRunService.start("ws_abc123", "session_abc123", "질문");

        assertThat(returned).isEqualTo(pending);
        verify(queryRunStore).markRunning("query_abc123");
        verify(queryRunStore).markCompleted("query_abc123", result);
        verify(queryEventBroker).complete("query_abc123");
    }

    @Test
    void start_pipelineFails_marksRunFailedAndBroadcastsFailure() {
        QueryRun pending = QueryRun.pending("query_abc123", "session_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("session_abc123", "질문")).thenReturn(pending);
        QueryService.QueryMessageContext messageContext = messageContext();
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123")).thenReturn(messageContext);
        PipelineQueryException error = new PipelineQueryException(
                "PIPELINE_UNAVAILABLE", "쿼리 파이프라인을 사용할 수 없습니다.", 503, null);
        when(queryService.query(eq("ws_abc123"), eq("session_abc123"), eq("질문"), eq("query_abc123"),
                eq("http://backend:8080/api/query/runs/query_abc123/events/callback"), eq(messageContext)))
                .thenThrow(error);

        queryRunService.start("ws_abc123", "session_abc123", "질문");

        verify(queryRunStore).markRunning("query_abc123");
        verify(queryRunStore).markFailed("query_abc123", "쿼리 파이프라인을 사용할 수 없습니다.");
        verify(queryEventBroker).fail("query_abc123", "쿼리 파이프라인을 사용할 수 없습니다.");
    }

    @Test
    void start_unexpectedFailure_marksRunFailedAndBroadcastsGeneralMessage() {
        QueryRun pending = QueryRun.pending("query_abc123", "session_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.create("session_abc123", "질문")).thenReturn(pending);
        QueryService.QueryMessageContext messageContext = messageContext();
        when(queryService.prepareMessages("session_abc123", "질문", "query_abc123")).thenReturn(messageContext);
        when(queryService.query(eq("ws_abc123"), eq("session_abc123"), eq("질문"), eq("query_abc123"),
                eq("http://backend:8080/api/query/runs/query_abc123/events/callback"), eq(messageContext)))
                .thenThrow(new IllegalStateException("DB 연결 종료"));

        queryRunService.start("ws_abc123", "session_abc123", "질문");

        verify(queryRunStore).markRunning("query_abc123");
        verify(queryRunStore).markFailed("query_abc123", "질의 처리 중 오류가 발생했습니다.");
        verify(queryEventBroker).fail("query_abc123", "질의 처리 중 오류가 발생했습니다.");
    }

    @Test
    void cleanupExpiredRuns_disposesBrokerChannelsForEvictedRuns() {
        when(queryRunStore.evictExpired()).thenReturn(java.util.List.of("query_old1", "query_old2"));

        queryRunService.cleanupExpiredRuns();

        verify(queryEventBroker).dispose("query_old1");
        verify(queryEventBroker).dispose("query_old2");
    }

    private QueryService.QueryMessageContext messageContext() {
        return new QueryService.QueryMessageContext(
                "pair_abc123", "chat_user_abc123", "chat_assistant_abc123", Instant.parse("2026-06-20T10:00:00Z"));
    }
}
