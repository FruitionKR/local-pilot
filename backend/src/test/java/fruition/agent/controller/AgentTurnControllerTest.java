package fruition.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.dto.AgentTurnResponse;
import fruition.agent.service.AgentTurnService;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.util.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentTurnController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class AgentTurnControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean AgentTurnService agentTurnService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    @Test
    void turn_authenticatedRequestReturnsPipelineResult() throws Exception {
        AgentTurnRequest request = request();
        AgentTurnResponse response = new AgentTurnResponse(
                "doc_1",
                4,
                "agent_request_1",
                objectMapper.readTree("{\"action\":\"markdown_edit\"}")
        );
        when(agentTurnService.turn(eq(WORKSPACE_ID), eq(USER_ID), any(AgentTurnRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("doc_1"))
                .andExpect(jsonPath("$.baseVersion").value(4))
                .andExpect(jsonPath("$.result.action").value("markdown_edit"));
    }

    @Test
    void turn_unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/agent/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isUnauthorized());
    }

    private AgentTurnRequest request() {
        return new AgentTurnRequest(
                "doc_1",
                4L,
                "문서를 점검해줘",
                null,
                new AgentTurnRequest.EditorSnapshot(
                        "# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2))
        );
    }
}
