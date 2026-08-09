package fruition.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.service.SkillService;
import fruition.util.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class SkillControllerTest {
    private static final String USER_ID = "user_1";
    private static final String WORKSPACE_ID = "ws_1";
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean SkillService skillService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    @Test
    void author_returnsProposalWithoutInternalTools() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class)))
                .thenReturn(objectMapper.readTree("{\"status\":\"proposal_ready\",\"name\":\"meeting-notes\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("proposal_ready"))
                .andExpect(jsonPath("$.allowed_tools").doesNotExist());
    }

    @Test
    void author_ignoresLegacyCapabilitiesField() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class)))
                .thenReturn(objectMapper.readTree("{\"status\":\"proposal_ready\"}"));
        String body = objectMapper.writeValueAsString(validRequest()).replace("}", ",\"capabilities\":[\"document-create\"]}");
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void author_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    private SkillAuthoringRequest validRequest() {
        return new SkillAuthoringRequest("personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
