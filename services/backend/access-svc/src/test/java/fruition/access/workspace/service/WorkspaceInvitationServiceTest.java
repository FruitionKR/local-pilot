package fruition.access.workspace.service;

import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.mail.WorkspaceInvitationSender;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceInvitation;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.InvitationAcceptResponse;
import fruition.access.workspace.dto.InvitationPreviewResponse;
import fruition.access.workspace.dto.WorkspaceInvitationCreateRequest;
import fruition.access.workspace.dto.WorkspaceInvitationResponse;
import fruition.access.workspace.exception.AlreadyMemberException;
import fruition.access.workspace.exception.InvitationAlreadyAcceptedException;
import fruition.access.workspace.exception.InvitationEmailMismatchException;
import fruition.access.workspace.exception.InvitationExpiredException;
import fruition.access.workspace.exception.InvitationInProgressException;
import fruition.access.workspace.exception.InvitationNotFoundException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceInvitationRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceInvitationServiceTest {

    private static final String WORKSPACE_ID = "ws_abc12345";
    private static final String OWNER_ID = "user_owner";
    private static final String INVITEE_ID = "user_invitee";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final long TTL_SECONDS = 604800;
    private static final String ACCEPT_URL = "http://localhost:3000/invitations";

    @Mock WorkspaceInvitationRepository invitationRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock WorkspaceRepository workspaceRepository;
    @Mock UserRepository userRepository;
    @Mock WorkspaceInvitationSender sender;
    @Mock AuthzProjectionStore authzProjectionStore;
    @Mock TransactionTemplate transactionTemplate;

    WorkspaceInvitationService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceInvitationService(
                invitationRepository, workspaceMemberRepository, workspaceRepository, userRepository,
                sender, authzProjectionStore, transactionTemplate, TTL_SECONDS, ACCEPT_URL);
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0)
                        .doInTransaction(null));
    }

    private Workspace workspace() {
        return new Workspace(WORKSPACE_ID, "팀 워크스페이스");
    }

    private User user(String id, String email) {
        return new User(id, email, User.PROVIDER_LOCAL, "표시이름", null);
    }

    private WorkspaceInvitation invitation(String token, Instant expiresAt) {
        return new WorkspaceInvitation("inv_1", WORKSPACE_ID, INVITEE_EMAIL, WorkspaceRole.MEMBER,
                OpaqueTokens.sha256(token), expiresAt, OWNER_ID);
    }

    private void ownerActor() {
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(WorkspaceRole.OWNER));
    }

    @Test
    void invite_sendsLinkWithGeneratedToken() {
        ownerActor();
        when(userRepository.findAllByEmail(INVITEE_EMAIL)).thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@example.com")));
        when(invitationRepository.findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
                WORKSPACE_ID, INVITEE_EMAIL)).thenReturn(Optional.empty());
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        WorkspaceInvitationResponse response = service.invite(OWNER_ID, WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.MEMBER));

        assertThat(response.email()).isEqualTo(INVITEE_EMAIL);
        assertThat(response.role()).isEqualTo("MEMBER");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(INVITEE_EMAIL), eq("팀 워크스페이스"), eq("표시이름"), url.capture());
        assertThat(url.getValue()).startsWith(ACCEPT_URL + "/");
        // 토큰 원문은 링크에만 실리고 DB에는 해시로 저장된다.
        String token = url.getValue().substring(ACCEPT_URL.length() + 1);
        ArgumentCaptor<WorkspaceInvitation> saved = ArgumentCaptor.forClass(WorkspaceInvitation.class);
        verify(invitationRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(INVITEE_EMAIL);
        assertThat(token).isNotBlank();
    }

    @Test
    void invite_uppercaseEmailIsNormalized() {
        ownerActor();
        when(userRepository.findAllByEmail(INVITEE_EMAIL)).thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@example.com")));
        when(invitationRepository.findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
                WORKSPACE_ID, INVITEE_EMAIL)).thenReturn(Optional.empty());
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.invite(OWNER_ID, WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest("  Invitee@Example.COM  ", WorkspaceRole.MEMBER));

        verify(sender).send(eq(INVITEE_EMAIL), any(), any(), any());
    }

    @Test
    void invite_pendingInvitationIsReissuedNotDuplicated() {
        ownerActor();
        when(userRepository.findAllByEmail(INVITEE_EMAIL)).thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@example.com")));
        WorkspaceInvitation pending = invitation("old-token", Instant.now().plusSeconds(60));
        when(invitationRepository.findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
                WORKSPACE_ID, INVITEE_EMAIL)).thenReturn(Optional.of(pending));

        service.invite(OWNER_ID, WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.OWNER));

        verify(invitationRepository, never()).save(any());
        // 재발송은 역할·만료를 갱신하고 옛 토큰을 무효화한다.
        assertThat(pending.getRole()).isEqualTo(WorkspaceRole.OWNER);
        assertThat(pending.getExpiresAt()).isAfter(Instant.now().plusSeconds(60));
        // 저장된 해시가 옛 토큰의 것과 달라야 옛 링크가 더 이상 조회되지 않는다.
        assertThat(pending.getTokenHash()).isNotEqualTo(OpaqueTokens.sha256("old-token"));
        // 새 해시는 이번에 메일로 나간 토큰의 것이다.
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(INVITEE_EMAIL), any(), any(), url.capture());
        String reissued = url.getValue().substring(ACCEPT_URL.length() + 1);
        assertThat(pending.getTokenHash()).isEqualTo(OpaqueTokens.sha256(reissued));
    }

    @Test
    void invite_nonOwnerGets403() {
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, "user_member"))
                .thenReturn(Optional.of(WorkspaceRole.MEMBER));

        assertThatThrownBy(() -> service.invite("user_member", WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.MEMBER)))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        verifyNoInteractions(sender);
    }

    @Test
    void invite_nonMemberGets404() {
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, "user_stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite("user_stranger", WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.MEMBER)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void invite_existingMemberGets409() {
        ownerActor();
        when(userRepository.findAllByEmail(INVITEE_EMAIL)).thenReturn(List.of(user(INVITEE_ID, INVITEE_EMAIL)));
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, INVITEE_ID))
                .thenReturn(Optional.of(WorkspaceRole.MEMBER));

        assertThatThrownBy(() -> service.invite(OWNER_ID, WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.MEMBER)))
                .isInstanceOf(AlreadyMemberException.class);
        verifyNoInteractions(sender);
    }

    @Test
    void preview_returnsWorkspaceAndInviter() {
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok")))
                .thenReturn(Optional.of(invitation("tok", Instant.now().plusSeconds(3600))));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@example.com")));

        InvitationPreviewResponse response = service.preview("tok");

        assertThat(response.workspaceName()).isEqualTo("팀 워크스페이스");
        assertThat(response.email()).isEqualTo(INVITEE_EMAIL);
        assertThat(response.invitedBy()).isEqualTo("표시이름");
    }

    @Test
    void preview_unknownTokenGets404() {
        when(invitationRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview("nope"))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void preview_revokedTokenGets404() {
        WorkspaceInvitation revoked = invitation("tok", Instant.now().plusSeconds(3600));
        revoked.revoke();
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok"))).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.preview("tok"))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void preview_expiredTokenGets410() {
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok")))
                .thenReturn(Optional.of(invitation("tok", Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> service.preview("tok"))
                .isInstanceOf(InvitationExpiredException.class);
    }

    @Test
    void accept_joinsWorkspaceAndEvictsProjection() {
        WorkspaceInvitation pending = invitation("tok", Instant.now().plusSeconds(3600));
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok"))).thenReturn(Optional.of(pending));
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(user(INVITEE_ID, INVITEE_EMAIL)));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, INVITEE_ID)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(INVITEE_ID)).thenReturn(user(INVITEE_ID, INVITEE_EMAIL));

        InvitationAcceptResponse response = service.accept(INVITEE_ID, "tok");

        assertThat(response.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(response.role()).isEqualTo("MEMBER");
        verify(workspaceMemberRepository).save(any(WorkspaceMember.class));
        verify(authzProjectionStore).evict(WORKSPACE_ID, INVITEE_ID);
        assertThat(pending.isAccepted()).isTrue();
    }

    @Test
    void accept_differentEmailGets403() {
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok")))
                .thenReturn(Optional.of(invitation("tok", Instant.now().plusSeconds(3600))));
        when(userRepository.findById("user_other")).thenReturn(Optional.of(user("user_other", "other@example.com")));

        assertThatThrownBy(() -> service.accept("user_other", "tok"))
                .isInstanceOf(InvitationEmailMismatchException.class);
        verify(workspaceMemberRepository, never()).save(any());
        verifyNoInteractions(authzProjectionStore);
    }

    @Test
    void accept_expiredGets410() {
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok")))
                .thenReturn(Optional.of(invitation("tok", Instant.now().minusSeconds(1))));
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(user(INVITEE_ID, INVITEE_EMAIL)));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, INVITEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(INVITEE_ID, "tok"))
                .isInstanceOf(InvitationExpiredException.class);
        verify(workspaceMemberRepository, never()).save(any());
    }

    @Test
    void accept_alreadyAcceptedByOtherRunGets409() {
        WorkspaceInvitation accepted = invitation("tok", Instant.now().plusSeconds(3600));
        accepted.accept("user_someone_else");
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok"))).thenReturn(Optional.of(accepted));
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(user(INVITEE_ID, INVITEE_EMAIL)));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, INVITEE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(INVITEE_ID, "tok"))
                .isInstanceOf(InvitationAlreadyAcceptedException.class);
    }

    @Test
    void accept_secondClickBySameUserIsIdempotent() {
        WorkspaceInvitation accepted = invitation("tok", Instant.now().plusSeconds(3600));
        accepted.accept(INVITEE_ID);
        when(invitationRepository.findByTokenHash(OpaqueTokens.sha256("tok"))).thenReturn(Optional.of(accepted));
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(user(INVITEE_ID, INVITEE_EMAIL)));
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, INVITEE_ID))
                .thenReturn(Optional.of(WorkspaceRole.MEMBER));

        InvitationAcceptResponse response = service.accept(INVITEE_ID, "tok");

        assertThat(response.workspaceId()).isEqualTo(WORKSPACE_ID);
        verify(workspaceMemberRepository, never()).save(any());
        verifyNoInteractions(authzProjectionStore);
    }

    @Test
    void revoke_pendingInvitationIsRevoked() {
        ownerActor();
        WorkspaceInvitation pending = invitation("tok", Instant.now().plusSeconds(3600));
        when(invitationRepository.findById("inv_1")).thenReturn(Optional.of(pending));

        service.revoke(OWNER_ID, WORKSPACE_ID, "inv_1");

        assertThat(pending.isRevoked()).isTrue();
    }

    @Test
    void revoke_otherWorkspaceInvitationGets404() {
        ownerActor();
        when(invitationRepository.findById("inv_1"))
                .thenReturn(Optional.of(new WorkspaceInvitation("inv_1", "ws_other", INVITEE_EMAIL,
                        WorkspaceRole.MEMBER, "hash", Instant.now().plusSeconds(3600), OWNER_ID)));

        assertThatThrownBy(() -> service.revoke(OWNER_ID, WORKSPACE_ID, "inv_1"))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void listPending_returnsPendingOnly() {
        ownerActor();
        when(invitationRepository.findByWorkspaceIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAt(WORKSPACE_ID))
                .thenReturn(List.of(invitation("tok", Instant.now().plusSeconds(3600))));

        assertThat(service.listPending(OWNER_ID, WORKSPACE_ID).invitations())
                .extracting(WorkspaceInvitationResponse::email)
                .containsExactly(INVITEE_EMAIL);
    }

    @Test
    void invite_concurrentDuplicateGets409NotServerError() {
        ownerActor();
        when(userRepository.findAllByEmail(INVITEE_EMAIL)).thenReturn(List.of());
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@example.com")));
        // 먼저 들어온 요청이 이미 대기 중 초대를 만들어 partial unique 제약에 걸린 상황.
        when(invitationRepository.findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
                WORKSPACE_ID, INVITEE_EMAIL)).thenReturn(Optional.empty());
        when(invitationRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.invite(OWNER_ID, WORKSPACE_ID,
                new WorkspaceInvitationCreateRequest(INVITEE_EMAIL, WorkspaceRole.MEMBER)))
                .isInstanceOf(InvitationInProgressException.class);
        verifyNoInteractions(sender);
    }
}
