package fruition.wikischema.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.util.GlobalExceptionHandler;
import fruition.wikischema.dto.WikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.service.WikiSchemaService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WikiSchemaController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class WikiSchemaControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WikiSchemaService wikiSchemaService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    @Test
    void preview_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.preview(eq(WORKSPACE_ID), eq(USER_ID), any(WikiSchemaPreviewRequest.class)))
                .thenReturn(objectMapper.readTree("{\"preview_markdown\":\"# 미리보기\",\"has_blocked_issues\":false}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest("# 원문"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview_markdown").value("# 미리보기"))
                .andExpect(jsonPath("$.has_blocked_issues").value(false));
    }

    @Test
    void createDraft_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.createDraft(eq(WORKSPACE_ID), eq(USER_ID), any(WikiSchemaDraftRequest.class)))
                .thenReturn(objectMapper.readTree("{\"wiki_schema\":{\"id\":\"sch_1\"}}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/drafts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaDraftRequest("# 원문", "기본"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wiki_schema.id").value("sch_1"));
    }

    @Test
    void activate_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.activate(WORKSPACE_ID, USER_ID, "sch_1"))
                .thenReturn(objectMapper.readTree("{\"id\":\"sch_1\",\"status\":\"active\"}"));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/sch_1/activate")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void getActive_authenticatedReturnsPipelineJson() throws Exception {
        when(wikiSchemaService.getActive(WORKSPACE_ID, USER_ID))
                .thenReturn(objectMapper.readTree("{\"id\":\"sch_1\",\"status\":\"active\"}"));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/active")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sch_1"));
    }

    @Test
    void preview_blankRawMarkdownReturns400() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest(" "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preview_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/wiki-schema/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WikiSchemaPreviewRequest("# 원문"))))
                .andExpect(status().isUnauthorized());
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }
}
