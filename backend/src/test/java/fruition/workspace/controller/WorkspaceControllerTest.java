package fruition.workspace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.security.JwtAuthenticationFilter;
import fruition.security.JwtTokenProvider;
import fruition.security.SecurityConfig;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.util.GlobalExceptionHandler;
import fruition.workspace.dto.WorkspaceCreateRequest;
import fruition.workspace.dto.WorkspaceListResponse;
import fruition.workspace.dto.WorkspaceLifecycleResponse;
import fruition.workspace.dto.WorkspaceRenameRequest;
import fruition.workspace.dto.WorkspaceResponse;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class WorkspaceControllerTest {

    private static final String USER_ID = "user_1f9a74af";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WorkspaceService workspaceService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void create_authenticated_returns201() throws Exception {
        when(workspaceService.create(eq(USER_ID), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "팀 워크스페이스", Instant.now(), Instant.now()));

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
                        new WorkspaceResponse("ws_aaa11111", "워크스페이스 A", Instant.now(), Instant.now()))));

        mockMvc.perform(get("/api/workspaces").header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaces[0].id").value("ws_aaa11111"));
    }

    @Test
    void rename_ownedWorkspace_returns200() throws Exception {
        when(workspaceService.rename(eq(USER_ID), eq("ws_aaa11111"), any())).thenReturn(
                new WorkspaceResponse("ws_aaa11111", "새 이름", Instant.now(), Instant.now()));

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
}
