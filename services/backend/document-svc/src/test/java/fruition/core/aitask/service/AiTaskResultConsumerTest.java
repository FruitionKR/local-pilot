package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.service.QueryEventBroker;
import fruition.core.query.service.QueryRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AiTaskResultConsumerTest {

    @Mock AiTaskResultApplier applier;
    @Mock QueryRunStore queryRunStore;
    @Mock QueryEventBroker queryEventBroker;

    private AiTaskResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AiTaskResultConsumer(
                new ObjectMapper(), applier, queryRunStore, queryEventBroker);
    }

    @Test
    void duplicateSuccessPublishesSseOnlyForFirstRedisTerminalTransition() throws Exception {
        QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
        var projection = new AiTaskResultApplier.QueryProjection("query-1", response, null);
        when(applier.applyQuery(org.mockito.ArgumentMatchers.any())).thenReturn(projection);
        when(queryRunStore.markCompleted("query-1", response)).thenReturn(true, false);

        consumer.consume("{\"kind\":\"query\"}");
        consumer.consume("{\"kind\":\"query\"}");

        verify(queryEventBroker).complete("query-1");
    }

    @Test
    void canonicalSuccessThenLateFailureDoesNotPublishContradictorySse() throws Exception {
        QueryResponse response = org.mockito.Mockito.mock(QueryResponse.class);
        when(applier.applyQuery(org.mockito.ArgumentMatchers.any())).thenReturn(
                new AiTaskResultApplier.QueryProjection("query-1", response, null),
                new AiTaskResultApplier.QueryProjection("query-1", null, "late failure"));
        when(queryRunStore.markCompleted("query-1", response)).thenReturn(true);
        when(queryRunStore.markFailed("query-1", "late failure")).thenReturn(false);

        consumer.consume("{\"kind\":\"query\"}");
        consumer.consume("{\"kind\":\"query\"}");

        verify(queryEventBroker).complete("query-1");
        verify(queryEventBroker, never()).fail("query-1", "late failure");
    }

    @Test
    void queryProgressPublishesSseLogWithoutApplyingTerminalResult() throws Exception {
        consumer.consume("""
                {
                  "event_id":"query:query-1:progress:1:wiki_loaded",
                  "kind":"query",
                  "run_id":"query-1",
                  "status":"progress",
                  "payload":{
                    "stage":"wiki_loaded",
                    "message":"Wiki 데이터를 불러왔습니다.",
                    "data":{"page_count":3}
                  }
                }
                """);

        verify(queryEventBroker).publish(
                "query-1",
                "query:query-1:progress:1:wiki_loaded",
                "wiki_loaded",
                "Wiki 데이터를 불러왔습니다.",
                java.util.Map.of("page_count", 3));
        verifyNoInteractions(applier, queryRunStore);
    }

    /**
     * error handler가 무한 재시도라, 진행 이벤트 중계 실패를 올리면 그 파티션의 최종 결과까지 막힌다.
     * 진행 이벤트는 유실을 허용하고 삼킨다.
     */
    @Test
    void queryProgressRelayFailureDoesNotStopTheConsumer() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("질의 진행 이벤트 ID는 필수입니다."))
                .when(queryEventBroker).publish(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        consumer.consume("""
                {"kind":"query","run_id":"query-1","status":"progress",
                 "payload":{"stage":"wiki_loaded","message":"불러왔습니다.","data":{}}}
                """);

        verifyNoInteractions(applier, queryRunStore);
    }

    @Test
    void consumeSetsFlowIdDuringProcessingAndClearsItAfterward() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(MDC.get("flowId")).isEqualTo("run-1");
            return null;
        }).when(applier).applyIngest(org.mockito.ArgumentMatchers.any());

        consumer.consume("{\"kind\":\"ingest\",\"run_id\":\"run-1\"}");

        assertThat(MDC.get("flowId")).isNull();
    }
}
