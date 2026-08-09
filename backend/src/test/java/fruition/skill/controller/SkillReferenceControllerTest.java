package fruition.skill.controller;

import fruition.agent.service.AgentServiceTokenVerifier;
import fruition.skill.service.SkillReferenceDocument;
import fruition.skill.service.SkillReferenceDocumentLoader;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.util.GlobalExceptionHandler;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillReferenceController.class)
@Import({AgentServiceTokenVerifier.class, GlobalExceptionHandler.class, SecurityConfig.class,
        JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
@TestPropertySource(properties = "app.agent.service-token=test-agent-token")
class SkillReferenceControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean WorkspaceMemberRepository memberRepository;
    @MockBean SkillReferenceDocumentLoader documentLoader;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    @Test
    void read_returnsAuthorizedCurrentMarkdown() throws Exception {
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(documentLoader.load("ws_1", List.of("doc_1")))
                .thenReturn(List.of(new SkillReferenceDocument("doc_1", "문서", "hash", "# 현재 본문")));

        mockMvc.perform(post("/internal/agent/skill-authoring/references/read")
                        .header("X-Agent-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace_id\":\"ws_1\",\"user_id\":\"user_1\",\"document_id\":\"doc_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("# 현재 본문"));
    }

    @Test
    void read_rejectsTokenBeforeRepositoryAccess() throws Exception {
        mockMvc.perform(post("/internal/agent/skill-authoring/references/read")
                        .header("X-Agent-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspace_id\":\"ws_1\",\"user_id\":\"user_1\",\"document_id\":\"doc_1\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(memberRepository, documentLoader);
    }
}
