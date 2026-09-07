package fruition.access.workspace.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.access.AccessExceptionHandler;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.InvitationAcceptResponse;
import fruition.access.workspace.dto.InvitationPreviewResponse;
import fruition.access.workspace.dto.WorkspaceInvitationCreateRequest;
import fruition.access.workspace.dto.WorkspaceInvitationListResponse;
import fruition.access.workspace.dto.WorkspaceInvitationResponse;
import fruition.access.workspace.exception.AlreadyMemberException;
import fruition.access.workspace.exception.InvitationEmailMismatchException;
import fruition.access.workspace.exception.InvitationExpiredException;
import fruition.access.workspace.exception.InvitationInProgressException;
import fruition.access.workspace.exception.InvitationSendException;
import fruition.access.workspace.exception.InvitationNotFoundException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.service.WorkspaceInvitationService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({WorkspaceInvitationController.class, InvitationController.class})
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class WorkspaceInvitationControllerTest {

    private static final String USER_ID = "user_1f9a74af";
    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String EMAIL = "invitee@example.com";
    private static final String TOKEN = "invite-token";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean WorkspaceInvitationService workspaceInvitationService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    private WorkspaceInvitationResponse invitation() {
        return new WorkspaceInvitationResponse("inv_1", EMAIL, "MEMBER",
                Instant.parse("2026-09-14T04:25:24Z"), Instant.parse("2026-09-07T04:25:24Z"));
    }

    @Test
    void invite_owner_returns201() throws Exception {
        when(workspaceInvitationService.invite(eq(USER_ID), eq(WORKSPACE_ID), any())).thenReturn(invitation());

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceInvitationCreateRequest(EMAIL, WorkspaceRole.MEMBER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitation_id").value("inv_1"))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.expires_at").value("2026-09-14T04:25:24Z"));
    }

    @Test
    void invite_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void invite_nonOwner_returns403() throws Exception {
        when(workspaceInvitationService.invite(eq(USER_ID), eq(WORKSPACE_ID), any()))
                .thenThrow(new WorkspaceAccessDeniedException("멤버를 초대하려면 OWNER 권한이 필요합니다."));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceInvitationCreateRequest(EMAIL, WorkspaceRole.MEMBER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void invite_alreadyMember_returns409() throws Exception {
        when(workspaceInvitationService.invite(eq(USER_ID), eq(WORKSPACE_ID), any()))
                .thenThrow(new AlreadyMemberException(EMAIL));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceInvitationCreateRequest(EMAIL, WorkspaceRole.MEMBER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_MEMBER"));
    }

    @Test
    void listPending_returnsInvitations() throws Exception {
        when(workspaceInvitationService.listPending(USER_ID, WORKSPACE_ID))
                .thenReturn(new WorkspaceInvitationListResponse(List.of(invitation())));

        mockMvc.perform(get("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitations[0].email").value(EMAIL));
    }

    @Test
    void revoke_returns204() throws Exception {
        doNothing().when(workspaceInvitationService).revoke(USER_ID, WORKSPACE_ID, "inv_1");

        mockMvc.perform(delete("/api/workspaces/" + WORKSPACE_ID + "/invitations/inv_1")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isNoContent());
        verify(workspaceInvitationService).revoke(USER_ID, WORKSPACE_ID, "inv_1");
    }

    /** 초대 링크 화면은 로그인 전에 열린다 — 인증 없이 200이어야 한다. */
    @Test
    void preview_unauthenticated_returns200() throws Exception {
        when(workspaceInvitationService.preview(TOKEN)).thenReturn(new InvitationPreviewResponse(
                WORKSPACE_ID, "팀 워크스페이스", EMAIL, "MEMBER", "홍길동", Instant.parse("2026-09-14T04:25:24Z")));

        mockMvc.perform(get("/api/invitations/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace_name").value("팀 워크스페이스"))
                .andExpect(jsonPath("$.invited_by").value("홍길동"))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void preview_unknownToken_returns404() throws Exception {
        when(workspaceInvitationService.preview(TOKEN)).thenThrow(new InvitationNotFoundException());

        mockMvc.perform(get("/api/invitations/" + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    void preview_expired_returns410() throws Exception {
        when(workspaceInvitationService.preview(TOKEN)).thenThrow(new InvitationExpiredException());

        mockMvc.perform(get("/api/invitations/" + TOKEN))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("INVITATION_EXPIRED"));
    }

    /** 수락은 어느 계정으로 들어오는지가 핵심이라 인증을 요구한다. */
    @Test
    void accept_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/invitations/" + TOKEN + "/accept"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accept_authenticated_returns200() throws Exception {
        when(workspaceInvitationService.accept(USER_ID, TOKEN))
                .thenReturn(new InvitationAcceptResponse(WORKSPACE_ID, "팀 워크스페이스", "MEMBER"));

        mockMvc.perform(post("/api/invitations/" + TOKEN + "/accept")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace_id").value(WORKSPACE_ID))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    void accept_emailMismatch_returns403() throws Exception {
        when(workspaceInvitationService.accept(USER_ID, TOKEN))
                .thenThrow(new InvitationEmailMismatchException(EMAIL));

        mockMvc.perform(post("/api/invitations/" + TOKEN + "/accept")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INVITATION_EMAIL_MISMATCH"));
    }

    /** 초대 실패인데 "인증번호 발송 실패" 문구가 나가면 사용자가 상황을 알 수 없다. */
    @Test
    void invite_mailSendFailure_returns502WithInvitationMessage() throws Exception {
        when(workspaceInvitationService.invite(eq(USER_ID), eq(WORKSPACE_ID), any()))
                .thenThrow(new InvitationSendException("초대 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요.", null));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceInvitationCreateRequest(EMAIL, WorkspaceRole.MEMBER))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("INVITATION_SEND_FAILED"))
                .andExpect(jsonPath("$.error.message").value("초대 메일 발송에 실패했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    void invite_concurrentDuplicate_returns409() throws Exception {
        when(workspaceInvitationService.invite(eq(USER_ID), eq(WORKSPACE_ID), any()))
                .thenThrow(new InvitationInProgressException(EMAIL));

        mockMvc.perform(post("/api/workspaces/" + WORKSPACE_ID + "/invitations")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WorkspaceInvitationCreateRequest(EMAIL, WorkspaceRole.MEMBER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_IN_PROGRESS"));
    }
}
