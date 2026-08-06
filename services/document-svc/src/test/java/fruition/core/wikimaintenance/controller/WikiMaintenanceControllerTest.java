package fruition.core.wikimaintenance.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.core.config.SecurityConfig;
import fruition.core.CoreExceptionHandler;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
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

@WebMvcTest(WikiMaintenanceController.class)
@Import({CoreExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
class WikiMaintenanceControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WikiMaintenanceService wikiMaintenanceService;

    @Test
    void lint_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiMaintenanceService.lint(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenReturn(objectMapper.readTree("{\"cluster_count\":2,\"orphan_refs\":[]}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiLintRequest(false, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster_count").value(2));
    }

    @Test
    void lint_authenticatedWithoutBodyReturnsPipelineJson() throws Exception {
        when(wikiMaintenanceService.lint(eq(WORKSPACE_ID), eq(USER_ID), any()))
                .thenReturn(objectMapper.readTree("{\"cluster_count\":0}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster_count").value(0));
    }

    @Test
    void lint_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki/maintenance/lint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiLintRequest(false, true))))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
