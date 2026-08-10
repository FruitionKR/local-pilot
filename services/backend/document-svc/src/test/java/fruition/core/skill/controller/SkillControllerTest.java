package fruition.core.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.CoreExceptionHandler;
import fruition.core.config.SecurityConfig;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.exception.PipelineSkillException;
import fruition.core.skill.service.SkillService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
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
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class SkillControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean SkillService skillService;

    @Test
    void author_forwardsPathWorkspaceAndPrincipalUser() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class)))
                .thenReturn(objectMapper.readTree("{\"status\":\"proposal_ready\",\"name\":\"meeting-notes\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SkillAuthoringRequest(
                                "personal", "meeting-notes", null,
                                "회의록 Skill을 만들어줘", "enhance", List.of("doc_1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("proposal_ready"))
                .andExpect(jsonPath("$.name").value("meeting-notes"));
    }

    @Test
    void author_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope_type\":\"personal\",\"instruction\":\"회의록 Skill을 만들어줘\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void author_pipelineValidationFailureUsesStandardErrorEnvelope() throws Exception {
        when(skillService.author(eq(WORKSPACE_ID), eq(USER_ID), any(SkillAuthoringRequest.class)))
                .thenThrow(new PipelineSkillException(
                        "Skill 요청이 거부되었습니다.", 422, "{\"detail\":[{\"type\":\"missing\"}]}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/skills/author")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SkillAuthoringRequest(
                                "personal", "meeting-notes", null,
                                "회의록 Skill을 만들어줘", "enhance", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("SKILL_REQUEST_REJECTED"))
                .andExpect(jsonPath("$.error.message").value("Skill 요청이 거부되었습니다."))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
