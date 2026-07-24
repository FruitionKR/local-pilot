package fruition.document.controller;

import fruition.document.dto.DocumentBlockResponse;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.service.DocumentService;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.util.GlobalExceptionHandler;
import fruition.workspace.exception.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class DocumentControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean DocumentService documentService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void getBlocks_existingDocument_returnsDocumentIdAndBlocksInOrder() throws Exception {
        DocumentBlocksResponse response = new DocumentBlocksResponse("doc_1f9a74af", List.of(
                new DocumentBlockResponse("B0005", "원본 문서의 다섯 번째 block 본문"),
                new DocumentBlockResponse("B0006", "원본 문서의 여섯 번째 block 본문")
        ));
        when(documentService.blocks(WORKSPACE_ID, USER_ID, "doc_1f9a74af")).thenReturn(response);

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc_1f9a74af"))
                .andExpect(jsonPath("$.blocks[0].block_id").value("B0005"))
                .andExpect(jsonPath("$.blocks[0].text").value("원본 문서의 다섯 번째 block 본문"))
                .andExpect(jsonPath("$.blocks[1].block_id").value("B0006"));
    }

    @Test
    void getBlocks_unknownDocument_returns404() throws Exception {
        when(documentService.blocks(WORKSPACE_ID, USER_ID, "doc_unknown"))
                .thenThrow(new DocumentNotFoundException("doc_unknown"));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_unknown/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void getBlocks_notOwnedWorkspace_returns404() throws Exception {
        when(documentService.blocks(any(), any(), any()))
                .thenThrow(new WorkspaceNotFoundException(WORKSPACE_ID));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void getBlocks_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents/doc_1f9a74af/blocks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_withQuery_passesFilenameSearchQuery() throws Exception {
        when(documentService.findAll(WORKSPACE_ID, USER_ID, "보고서"))
                .thenReturn(new fruition.document.dto.DocumentListResponse(List.of()));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/documents")
                        .queryParam("query", "보고서")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray());

        verify(documentService).findAll(WORKSPACE_ID, USER_ID, "보고서");
    }
}
