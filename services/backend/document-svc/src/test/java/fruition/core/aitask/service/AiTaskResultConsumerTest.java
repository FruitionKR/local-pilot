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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
