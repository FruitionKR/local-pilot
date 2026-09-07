package fruition.access.workspace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.access.AccessExceptionHandler;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceMemberListResponse;
import fruition.access.workspace.dto.WorkspaceMemberResponse;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.exception.LastOwnerException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceMemberNotFoundException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.service.WorkspaceMemberService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceMemberController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class WorkspaceMemberControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String TARGET_ID = "user_target";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WorkspaceMemberService workspaceMemberService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private WorkspaceMemberResponse response(String userId, String role) {
        return new WorkspaceMemberResponse(
                userId, userId + "@example.com", "표시이름", "local", role, Instant.parse("2026-08-13T04:25:24Z"));
    }

    @Test
    void list_member_returnsMembers() throws Exception {
        when(workspaceMemberService.list(USER_ID, WORKSPACE_ID)).thenReturn(
                new WorkspaceMemberListResponse(List.of(response(USER_ID, "OWNER"), response(TARGET_ID, "MEMBER"))));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/members")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].user_id").value(USER_ID))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].display_name").value("표시이름"))
                .andExpect(jsonPath("$.members[0].provider").value("local"))
                .andExpect(jsonPath("$.members[1].user_id").value(TARGET_ID));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/members"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_nonMember_returns404() throws Exception {
        when(workspaceMemberService.list(USER_ID, WORKSPACE_ID))
                .thenThrow(new WorkspaceNotFoundException(WORKSPACE_ID));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/members")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void changeRole_owner_returns200() throws Exception {
        when(workspaceMemberService.changeRole(eq(USER_ID), eq(WORKSPACE_ID), eq(TARGET_ID), any()))
                .thenReturn(response(TARGET_ID, "OWNER"));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void changeRole_missingRole_returns400() throws Exception {
        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void changeRole_nonOwner_returns403() throws Exception {
        when(workspaceMemberService.changeRole(eq(USER_ID), eq(WORKSPACE_ID), eq(TARGET_ID), any()))
                .thenThrow(new WorkspaceAccessDeniedException("멤버 역할을 변경하려면 OWNER 권한이 필요합니다."));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void changeRole_lastOwner_returns409() throws Exception {
        when(workspaceMemberService.changeRole(eq(USER_ID), eq(WORKSPACE_ID), eq(TARGET_ID), any()))
                .thenThrow(new LastOwnerException(WORKSPACE_ID));

        mockMvc.perform(patch("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_OWNER"));
    }

    @Test
    void remove_returns204() throws Exception {
        doNothing().when(workspaceMemberService).remove(USER_ID, WORKSPACE_ID, TARGET_ID);

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNoContent());
        verify(workspaceMemberService).remove(USER_ID, WORKSPACE_ID, TARGET_ID);
    }

    @Test
    void remove_targetNotMember_returns404() throws Exception {
        doThrow(new WorkspaceMemberNotFoundException(WORKSPACE_ID, TARGET_ID))
                .when(workspaceMemberService).remove(USER_ID, WORKSPACE_ID, TARGET_ID);

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/members/" + TARGET_ID)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_MEMBER_NOT_FOUND"));
    }

    @Test
    void remove_lastOwner_returns409() throws Exception {
        doThrow(new LastOwnerException(WORKSPACE_ID))
                .when(workspaceMemberService).remove(USER_ID, WORKSPACE_ID, USER_ID);

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/members/" + USER_ID)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_OWNER"));
    }
}
