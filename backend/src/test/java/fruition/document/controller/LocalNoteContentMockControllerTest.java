package fruition.document.controller;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("local")
@WebMvcTest(LocalNoteContentMockController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class LocalNoteContentMockControllerTest {

    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
    }

    @Test
    void getContent_missingDraft_returns404() throws Exception {
        mockMvc.perform(get(contentPath("doc_missing"))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTE_DRAFT_NOT_FOUND"));
    }

    @Test
    void updateContent_matchingVersion_savesAndIncrementsVersion() throws Exception {
        String documentId = "doc_save";
        mockMvc.perform(put(contentPath(documentId))
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"markdown":"# 변경된 문서\\n","expected_content_version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value(documentId))
                .andExpect(jsonPath("$.markdown").value("# 변경된 문서\n"))
                .andExpect(jsonPath("$.content_version").value(1));

        mockMvc.perform(get(contentPath(documentId))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content_version").value(1));
    }

    @Test
    void updateContent_staleVersion_returns409AndKeepsCurrentDraft() throws Exception {
        String documentId = "doc_conflict";
        mockMvc.perform(put(contentPath(documentId))
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"markdown":"first","expected_content_version":0}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put(contentPath(documentId))
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"markdown":"stale","expected_content_version":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_CONTENT_VERSION_CONFLICT"));

        mockMvc.perform(get(contentPath(documentId))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value("first"));
    }

    @Test
    void contentEndpoints_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(contentPath("doc_unauthenticated")))
                .andExpect(status().isUnauthorized());
    }

    private String contentPath(String documentId) {
        return "/api/workspaces/" + WORKSPACE_ID + "/documents/" + documentId + "/content";
    }
}
