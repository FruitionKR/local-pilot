package fruition.query.controller;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
import fruition.query.service.QueryEventBroker;
import fruition.query.service.QueryRunService;
import fruition.query.service.QueryRunStore;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.util.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryRunController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class QueryRunControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean QueryRunService queryRunService;
    @MockBean QueryRunStore queryRunStore;
    @MockBean QueryEventBroker queryEventBroker;

    @Test
    void createRun_returns202WithRequestIdAndPendingStatus() throws Exception {
        QueryRun run = QueryRun.pending("query_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunService.start("질문")).thenReturn(run);

        mockMvc.perform(post("/api/query/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.request_id").value("query_abc123"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void getRun_unknownRequestId_returns404() throws Exception {
        when(queryRunStore.find("query_unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/query/runs/query_unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("QUERY_RUN_NOT_FOUND"));
    }

    @Test
    void getRun_completedRun_returnsResult() throws Exception {
        QueryResponse result = new QueryResponse(null, null, null, null, null, null);
        QueryRun run = QueryRun.pending("query_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"))
                .running()
                .completed(result, Instant.parse("2026-06-20T10:00:05Z"));
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/query/runs/query_abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value("query_abc123"))
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void subscribe_unknownRequestId_returns404() throws Exception {
        when(queryRunStore.find("query_unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/query/runs/query_unknown/events"))
                .andExpect(status().isNotFound());
    }

    @Test
    void subscribe_existingRun_startsAsyncSseResponse() throws Exception {
        QueryRun run = QueryRun.pending("query_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(run));
        when(queryEventBroker.subscribe("query_abc123")).thenReturn(new SseEmitter(0L));

        mockMvc.perform(get("/api/query/runs/query_abc123/events"))
                .andExpect(request().asyncStarted());
    }

    @Test
    void receiveCallback_unknownRequestId_returns404() throws Exception {
        when(queryRunStore.find("query_unknown")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/query/runs/query_unknown/events/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_type\":\"query.log\",\"stage\":\"query_started\",\"message\":\"시작\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void receiveCallback_existingRun_publishesToBroker() throws Exception {
        QueryRun run = QueryRun.pending("query_abc123", "질문", Instant.parse("2026-06-20T10:00:00Z"));
        when(queryRunStore.find("query_abc123")).thenReturn(Optional.of(run));

        mockMvc.perform(post("/api/query/runs/query_abc123/events/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_type\":\"query.log\",\"stage\":\"query_started\",\"message\":\"시작\"}"))
                .andExpect(status().isOk());

        verify(queryEventBroker).publish(eq("query_abc123"), eq("query_started"), eq("시작"), any());
    }
}
