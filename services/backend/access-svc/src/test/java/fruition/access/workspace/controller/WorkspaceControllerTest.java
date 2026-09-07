package fruition.access.workspace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.AccessExceptionHandler;
import fruition.access.workspace.dto.WorkspaceCreateRequest;
import fruition.access.workspace.dto.WorkspaceIconUpdateRequest;
import fruition.access.workspace.dto.WorkspaceListResponse;
import fruition.access.workspace.dto.WorkspaceLifecycleResponse;
import fruition.access.workspace.dto.WorkspaceRenameRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.exception.UnsupportedWorkspaceIconException;
import fruition.access.workspace.exception.WorkspaceIconNotFoundException;
import fruition.access.workspace.exception.WorkspaceIconTooLargeException;
import org.springframework.http.HttpMethod;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.service.WorkspaceIconService;
import fruition.access.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class WorkspaceControllerTest {

    private static final String USER_ID = "user_1f9a74af";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WorkspaceService workspaceService;
    @MockBean WorkspaceIconService workspaceIconService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void create_authenticated_returns201() throws Exception {
        when(workspaceService.create(eq(USER_ID), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "팀 워크스페이스", null, null, Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceCreateRequest("팀 워크스페이스"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ws_aaa11111"))
                .andExpect(jsonPath("$.name").value("팀 워크스페이스"));
    }

    @Test
    void create_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceCreateRequest("팀 워크스페이스"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_authenticated_returnsWorkspaces() throws Exception {
        when(workspaceService.list(USER_ID)).thenReturn(
                new WorkspaceListResponse(List.of(
                        new WorkspaceResponse("ws_aaa11111", "워크스페이스 A", "🌱", null, Instant.now(), Instant.now()))));

        mockMvc.perform(get("/api/workspaces").header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].id").value("ws_aaa11111"))
                .andExpect(jsonPath("$.workspaces[0].icon_emoji").value("🌱"));
    }

    @Test
    void rename_ownedWorkspace_returns200() throws Exception {
        when(workspaceService.rename(eq(USER_ID), eq("ws_aaa11111"), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "새 이름", null, null, Instant.now(), Instant.now()));

        mockMvc.perform(patch("/api/workspaces/ws_aaa11111")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceRenameRequest("새 이름"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"));
    }

    @Test
    void rename_notOwnedWorkspace_returns404() throws Exception {
        when(workspaceService.rename(eq(USER_ID), eq("ws_unknown"), any()))
                .thenThrow(new WorkspaceNotFoundException("ws_unknown"));

        mockMvc.perform(patch("/api/workspaces/ws_unknown")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceRenameRequest("새 이름"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void delete_ownedWorkspace_returnsSoftDeleteState() throws Exception {
        when(workspaceService.delete(USER_ID, "ws_aaa11111", "delete-key"))
                .thenReturn(new WorkspaceLifecycleResponse(
                        "ws_aaa11111", true, Instant.now()));

        mockMvc.perform(delete("/api/workspaces/ws_aaa11111")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "delete-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ws_aaa11111"))
                .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void restore_deletedWorkspace_returnsActiveState() throws Exception {
        when(workspaceService.restore(USER_ID, "ws_aaa11111", "restore-key"))
                .thenReturn(new WorkspaceLifecycleResponse(
                        "ws_aaa11111", false, null));

        mockMvc.perform(post("/api/workspaces/ws_aaa11111/restore")
                        .header("Authorization", bearerToken())
                        .header("Idempotency-Key", "restore-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deleted_at").doesNotExist());
    }

    @Test
    void updateIcon_ownedWorkspace_returns200() throws Exception {
        when(workspaceIconService.updateIcon(eq(USER_ID), eq("ws_aaa11111"), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "워크스페이스", "📁", null, Instant.now(), Instant.now()));

        mockMvc.perform(put("/api/workspaces/ws_aaa11111/icon")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceIconUpdateRequest("📁"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon_emoji").value("📁"));
    }

    @Test
    void updateIcon_nullClears_returns200() throws Exception {
        when(workspaceIconService.updateIcon(eq(USER_ID), eq("ws_aaa11111"), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "워크스페이스", null, null, Instant.now(), Instant.now()));

        mockMvc.perform(put("/api/workspaces/ws_aaa11111/icon")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon_emoji\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon_emoji").doesNotExist());
    }

    @Test
    void updateIcon_withWhitespace_returns400() throws Exception {
        mockMvc.perform(put("/api/workspaces/ws_aaa11111/icon")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon_emoji\":\"팀 아이콘\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void updateIcon_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/workspaces/ws_aaa11111/icon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceIconUpdateRequest("📁"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateIcon_notOwnedWorkspace_returns404() throws Exception {
        when(workspaceIconService.updateIcon(eq(USER_ID), eq("ws_unknown"), any()))
                .thenThrow(new WorkspaceNotFoundException("ws_unknown"));

        mockMvc.perform(put("/api/workspaces/ws_unknown/icon")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WorkspaceIconUpdateRequest("📁"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

    @Test
    void updateIconImage_returns200WithIconUrl() throws Exception {
        when(workspaceIconService.updateIconImage(eq(USER_ID), eq("ws_aaa11111"), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "워크스페이스", null,
                        "/api/workspaces/ws_aaa11111/icon/image", Instant.now(), Instant.now()));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/workspaces/ws_aaa11111/icon/image")
                        .file(new MockMultipartFile("file", "icon.png", "image/png", PNG_BYTES))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon_url").value("/api/workspaces/ws_aaa11111/icon/image"))
                .andExpect(jsonPath("$.icon_emoji").doesNotExist());
    }

    @Test
    void updateIconImage_unsupportedFormat_returns400() throws Exception {
        when(workspaceIconService.updateIconImage(eq(USER_ID), eq("ws_aaa11111"), any()))
                .thenThrow(new UnsupportedWorkspaceIconException("PNG, JPEG, WebP, GIF 이미지만 아이콘으로 쓸 수 있습니다."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/workspaces/ws_aaa11111/icon/image")
                        .file(new MockMultipartFile("file", "icon.txt", "image/png", "nope".getBytes()))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_WORKSPACE_ICON"));
    }

    @Test
    void updateIconImage_tooLarge_returns413() throws Exception {
        when(workspaceIconService.updateIconImage(eq(USER_ID), eq("ws_aaa11111"), any()))
                .thenThrow(new WorkspaceIconTooLargeException(1024L * 1024));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/workspaces/ws_aaa11111/icon/image")
                        .file(new MockMultipartFile("file", "icon.png", "image/png", PNG_BYTES))
                        .header("Authorization", bearerToken()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ICON_TOO_LARGE"));
    }

    @Test
    void updateIconImage_unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/workspaces/ws_aaa11111/icon/image")
                        .file(new MockMultipartFile("file", "icon.png", "image/png", PNG_BYTES)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getIconImage_returnsBytesWithEtag() throws Exception {
        when(workspaceIconService.readIconImage(USER_ID, "ws_aaa11111"))
                .thenReturn(new WorkspaceIconService.IconImage(PNG_BYTES, "image/png", "hash-1"));

        mockMvc.perform(get("/api/workspaces/ws_aaa11111/icon/image")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"hash-1\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void getIconImage_matchingEtag_returns304() throws Exception {
        when(workspaceIconService.readIconImage(USER_ID, "ws_aaa11111"))
                .thenReturn(new WorkspaceIconService.IconImage(PNG_BYTES, "image/png", "hash-1"));

        mockMvc.perform(get("/api/workspaces/ws_aaa11111/icon/image")
                        .header("Authorization", bearerToken())
                        .header("If-None-Match", "\"hash-1\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void getIconImage_missingIcon_returns404() throws Exception {
        when(workspaceIconService.readIconImage(USER_ID, "ws_aaa11111"))
                .thenThrow(new WorkspaceIconNotFoundException("ws_aaa11111"));

        mockMvc.perform(get("/api/workspaces/ws_aaa11111/icon/image")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ICON_NOT_FOUND"));
    }
}
