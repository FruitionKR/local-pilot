package fruition.query.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.chat.domain.ChatSession;
import fruition.chat.exception.ChatSessionNotFoundException;
import fruition.chat.service.ChatSessionService;
import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryRequest;
import fruition.query.dto.QueryResponse;
import fruition.query.service.QueryRunService;
import fruition.query.service.QueryService;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.CustomOAuth2UserService;
import fruition.security.oauth.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.util.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QueryController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class QueryControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String SESSION_ID = "session_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean QueryService queryService;
    @MockBean QueryRunService queryRunService;
    @MockBean ChatSessionService chatSessionService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private String basePath() {
        return "/api/workspaces/" + WORKSPACE_ID + "/chat/sessions/" + SESSION_ID;
    }

    @Test
    void query_ownedSession_returns200() throws Exception {
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryResponse response = new QueryResponse(
                new QueryResponse.MessageSummary("chat_user_1", "user", "질문", "completed", Instant.now()),
                new QueryResponse.MessageSummary("chat_assistant_1", "assistant", "답변", "completed", Instant.now()),
                null, null, null, null);
        when(queryService.query(eq(SESSION_ID), eq("질문"))).thenReturn(response);

        mockMvc.perform(post(basePath() + "/query")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistant_message.content").value("답변"));
    }

    @Test
    void query_notOwnedSession_returns404() throws Exception {
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenThrow(new ChatSessionNotFoundException(SESSION_ID));

        mockMvc.perform(post(basePath() + "/query")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    void query_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(basePath() + "/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRun_ownedSession_returns202() throws Exception {
        when(chatSessionService.verifyOwnedSession(WORKSPACE_ID, USER_ID, SESSION_ID))
                .thenReturn(new ChatSession(SESSION_ID, WORKSPACE_ID, USER_ID, null));
        QueryRun run = QueryRun.pending("query_abc123", SESSION_ID, "질문", Instant.now());
        when(queryRunService.start(SESSION_ID, "질문")).thenReturn(run);

        mockMvc.perform(post(basePath() + "/query/runs")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new QueryRequest("질문"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.request_id").value("query_abc123"))
                .andExpect(jsonPath("$.status").value("pending"));
    }
}
