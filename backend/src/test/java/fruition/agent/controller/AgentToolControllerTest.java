package fruition.agent.controller;

import fruition.agent.service.AgentServiceTokenVerifier;
import fruition.agent.service.AgentToolService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentToolController.class)
@Import({AgentServiceTokenVerifier.class, GlobalExceptionHandler.class, SecurityConfig.class,
        JwtAuthenticationFilter.class, JwtTokenProvider.class, OAuthExchangeCodeStore.class,
        OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
@TestPropertySource(properties = "app.agent.service-token=test-agent-token")
class AgentToolControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean AgentToolService toolService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    @Test
    void read_forwardsAuthenticatedRequest() throws Exception {
        when(toolService.read(eq("list_root_items"), any())).thenReturn(Map.of("items", "ok"));

        mockMvc.perform(post("/internal/agent/tools/read/list_root_items")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run_1","workspace_id":"ws_1","user_id":"user_1","arguments":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value("ok"));
    }

    @Test
    void execute_rejectsInvalidTokenBeforeServiceAccess() throws Exception {
        mockMvc.perform(post("/internal/agent/tools/execute/move_document")
                        .header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"run_id":"run_1","workspace_id":"ws_1","user_id":"user_1",
                                 "plan_id":"plan_1","plan_version":1,"operation_hash":"hash",
                                 "operation_id":"operation_1","idempotency_key":"idem_1","arguments":{}}
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(toolService);
    }
}
