package fruition.document.controller;

import fruition.document.dto.DocumentBlockResponse;
import fruition.document.dto.DocumentBlocksResponse;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.DocumentContentSaveResponse;
import fruition.document.dto.DocumentDuplicateResponse;
import fruition.document.dto.DocumentExportResult;
import fruition.document.dto.DocumentLifecycleRequest;
import fruition.document.dto.DocumentLifecycleResponse;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.DocumentRenameResponse;
import fruition.document.dto.MarkdownDocumentCreateRequest;
import fruition.document.domain.DocumentRole;
import fruition.document.domain.DocumentStatus;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.service.DocumentService;
import fruition.document.service.DocumentExportService;
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
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.time.Instant;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(DocumentController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class DocumentControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean DocumentService documentService;
    @MockBean DocumentExportService documentExportService;
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
    void export_returnsUtf8MarkdownWithEncodedKoreanFilename() throws Exception {
        byte[] bytes = "# 최신 본문\n한글".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(documentExportService.exportMarkdown(
                WORKSPACE_ID, USER_ID, "doc_export"))
                .thenReturn(new DocumentExportResult("회의 결과.md", bytes));

        mockMvc.perform(get(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_export/export")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION, containsString("filename*=")));
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

    @Test
    void createMarkdown_passesIdempotencyKeyAndReturnsCreatedDocument() throws Exception {
        DocumentUploadResponse response = new DocumentUploadResponse(
                "doc_created",
                "새 문서.md",
                "text/markdown",
                0,
                DocumentStatus.completed,
                null,
                Instant.now(),
                true,
                1,
                DocumentRole.EDITABLE
        );
        when(documentService.createMarkdown(
                eq(WORKSPACE_ID), eq(USER_ID), eq("create-key"), any(MarkdownDocumentCreateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/documents/markdown")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "create-key")
                        .contentType("application/json")
                        .content("""
                                {"display_name":"새 문서","markdown":""}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("doc_created"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.current_version").value(1))
                .andExpect(jsonPath("$.source_uri").doesNotExist());

        verify(documentService).createMarkdown(
                eq(WORKSPACE_ID), eq(USER_ID), eq("create-key"), any(MarkdownDocumentCreateRequest.class));
    }

    @Test
    void duplicate_passesIdempotencyKeyAndReturnsCreatedDocument() throws Exception {
        when(documentService.duplicate(
                WORKSPACE_ID, USER_ID, "doc_source", "duplicate-key"))
                .thenReturn(new DocumentDuplicateResponse(
                        "doc_copy",
                        "보고서 복사본.md",
                        "보고서 복사본",
                        "text/markdown",
                        12,
                        1,
                        "doc_source",
                        4
                ));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_source/duplicate")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "duplicate-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("doc_copy"))
                .andExpect(jsonPath("$.filename").value("보고서 복사본.md"))
                .andExpect(jsonPath("$.display_name").value("보고서 복사본"))
                .andExpect(jsonPath("$.current_version").value(1))
                .andExpect(jsonPath("$.source_document_id").value("doc_source"))
                .andExpect(jsonPath("$.sort_order").value(4));

        verify(documentService).duplicate(
                WORKSPACE_ID, USER_ID, "doc_source", "duplicate-key");
    }

    @Test
    void delete_passesBaseVersionAndIdempotencyKey() throws Exception {
        when(documentService.delete(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("doc_delete"),
                eq("delete-key"),
                any(DocumentLifecycleRequest.class)
        )).thenReturn(new DocumentLifecycleResponse(
                "doc_delete", 2, true, Instant.now(), 3));

        mockMvc.perform(delete(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_delete")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "delete-key")
                        .contentType("application/json")
                        .content("""
                                {"base_version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void restore_passesBaseVersionAndIdempotencyKey() throws Exception {
        when(documentService.restore(
                eq(WORKSPACE_ID),
                eq(USER_ID),
                eq("doc_restore"),
                eq("restore-key"),
                any(DocumentLifecycleRequest.class)
        )).thenReturn(new DocumentLifecycleResponse(
                "doc_restore", 3, false, null, 5));

        mockMvc.perform(post(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_restore/restore")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "restore-key")
                        .contentType("application/json")
                        .content("""
                                {"base_version":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_version").value(3))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.sort_order").value(5));
    }

    @Test
    void saveContent_multipartPassesMarkdownAndBaseVersion() throws Exception {
        Instant updatedAt = Instant.now();
        when(documentService.saveContent(
                WORKSPACE_ID, USER_ID, "doc_edit", "# 변경\n", 3L, null))
                .thenReturn(new DocumentContentSaveResponse(
                        "doc_edit", 4, "a".repeat(64), updatedAt, true));
        MockMultipartFile markdown = new MockMultipartFile(
                "markdown", "", "text/plain",
                "# 변경\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile baseVersion = new MockMultipartFile(
                "base_version", "", "text/plain",
                "3".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(markdown)
                        .file(baseVersion)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc_edit"))
                .andExpect(jsonPath("$.current_version").value(4))
                .andExpect(jsonPath("$.changed").value(true));

        verify(documentService).saveContent(
                WORKSPACE_ID, USER_ID, "doc_edit", "# 변경\n", 3L, null);
    }

    @Test
    void saveContent_invalidBaseVersion_returns400() throws Exception {
        MockMultipartFile markdown = new MockMultipartFile(
                "markdown", "", "text/plain",
                "# 본문\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile baseVersion = new MockMultipartFile(
                "base_version", "", "text/plain",
                "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(multipart(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/content")
                        .file(markdown)
                        .file(baseVersion)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("Authorization", bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DOCUMENT_VERSION"));
    }

    @Test
    void rename_usesDisplayNameAndBaseVersion() throws Exception {
        Instant updatedAt = Instant.now();
        when(documentService.rename(
                eq(WORKSPACE_ID), eq(USER_ID), eq("doc_edit"), any(DocumentRenameRequest.class)))
                .thenReturn(new DocumentRenameResponse(
                        "doc_edit", "새 제목.md", "새 제목", 2, updatedAt, true));

        mockMvc.perform(patch(
                        "/api/workspaces/" + WORKSPACE_ID + "/documents/doc_edit/rename")
                        .header("Authorization", bearerToken())
                        .contentType("application/json")
                        .content("""
                                {"display_name":"새 제목","base_version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("새 제목.md"))
                .andExpect(jsonPath("$.display_name").value("새 제목"))
                .andExpect(jsonPath("$.current_version").value(2))
                .andExpect(jsonPath("$.changed").value(true));
    }
}
