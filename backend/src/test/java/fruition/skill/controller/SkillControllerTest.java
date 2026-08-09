package fruition.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.skill.dto.SkillDraftRequest;
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
    void review_authenticatedReturnsPipelineResult() throws Exception {
        when(skillService.review(eq(WORKSPACE_ID), eq(USER_ID), any(SkillDraftRequest.class)))
                .thenReturn(objectMapper.readTree("{\"publish_allowed\":true,\"review_token\":\"token\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/reviews")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publish_allowed").value(true))
                .andExpect(jsonPath("$.review_token").value("token"));
    }

    @Test
    void refine_nameOver63CharactersReturns400() throws Exception {
        SkillDraftRequest request = new SkillDraftRequest(
                null, "가".repeat(64), "지시사항", "personal", List.of(), null, null, null);

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/refine")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refine_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/refine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());
    }

    private SkillDraftRequest validRequest() {
        return new SkillDraftRequest(
                "meeting-summary", "회의 정리", "회의 내용을 정리한다.", "personal",
                List.of("doc_1"), null, null, null);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
