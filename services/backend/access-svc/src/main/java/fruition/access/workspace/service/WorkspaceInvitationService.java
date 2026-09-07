package fruition.access.workspace.service;

import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.mail.WorkspaceInvitationSender;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceInvitation;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.InvitationAcceptResponse;
import fruition.access.workspace.dto.InvitationPreviewResponse;
import fruition.access.workspace.dto.WorkspaceInvitationCreateRequest;
import fruition.access.workspace.dto.WorkspaceInvitationListResponse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class WorkspaceInvitationService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInvitationService.class);

    private final WorkspaceInvitationRepository invitationRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final WorkspaceInvitationSender sender;
    private final AuthzProjectionStore authzProjectionStore;
    private final TransactionTemplate transactionTemplate;
    private final long ttlSeconds;
    private final String acceptUrlBase;

    public WorkspaceInvitationService(
            WorkspaceInvitationRepository invitationRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            WorkspaceInvitationSender sender,
            AuthzProjectionStore authzProjectionStore,
            TransactionTemplate transactionTemplate,
            @Value("${app.workspace.invitation.ttl-seconds}") long ttlSeconds,
            @Value("${app.workspace.invitation.accept-url}") String acceptUrlBase) {
        this.invitationRepository = invitationRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.authzProjectionStore = authzProjectionStore;
        this.transactionTemplate = transactionTemplate;
        this.ttlSeconds = ttlSeconds;
        this.acceptUrlBase = acceptUrlBase;
    }

    public WorkspaceInvitationResponse invite(
            String actorId, String workspaceId, WorkspaceInvitationCreateRequest request) {
        requireOwner(workspaceId, actorId);

        String email = request.email().trim().toLowerCase();
        if (hasMemberAccount(workspaceId, email)) {
            throw new AlreadyMemberException(email);
        }

        Workspace workspace = activeWorkspace(workspaceId);
        String inviterName = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException(actorId))
                .getDisplayName();

        String token = OpaqueTokens.generate();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        // DB 쓰기만 트랜잭션으로 처리하고 커밋한다. SMTP 발송을 트랜잭션 안에서 하면
        // 외부 메일 서버 왕복 동안 DB 커넥션을 붙잡는다(EmailVerificationService와 같은 이유).
        WorkspaceInvitation invitation;
        try {
            invitation = transactionTemplate.execute(status ->
                    invitationRepository
                            .findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(workspaceId, email)
                            .map(pending -> {
                                // 재초대는 새 행이 아니라 재발송이다. 옛 링크는 이 시점에 무효가 된다.
                                pending.reissue(OpaqueTokens.sha256(token), expiresAt, request.role(), actorId);
                                return pending;
                            })
                            .orElseGet(() -> invitationRepository.save(new WorkspaceInvitation(
                                    "inv_" + UUID.randomUUID().toString().replace("-", ""),
                                    workspaceId, email, request.role(),
                                    OpaqueTokens.sha256(token), expiresAt, actorId))));
        } catch (DataIntegrityViolationException e) {
            // 같은 주소로 동시에 초대가 들어오면 대기 중 초대 partial unique 제약에 걸린다.
            // 먼저 들어온 요청이 이미 메일을 보냈으므로 500이 아니라 409로 알린다.
            throw new InvitationInProgressException(email);
        }

        // 발송 실패 시 예외를 전파한다. 초대 행은 남지만 재초대로 덮어쓰이고 만료로 정리된다.
        sender.send(email, workspace.getName(), inviterName, acceptUrlBase + "/" + token);
        log.info("[초대 발송] invitationId={} workspaceId={} role={}",
                invitation.getId(), workspaceId, request.role());

        return WorkspaceInvitationResponse.from(invitation);
    }

    public WorkspaceInvitationListResponse listPending(String actorId, String workspaceId) {
        requireOwner(workspaceId, actorId);
        return new WorkspaceInvitationListResponse(
                invitationRepository
                        .findByWorkspaceIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAt(workspaceId)
                        .stream()
                        .map(WorkspaceInvitationResponse::from)
                        .toList()
        );
    }

    @Transactional
    public void revoke(String actorId, String workspaceId, String invitationId) {
        requireOwner(workspaceId, actorId);

        WorkspaceInvitation invitation = invitationRepository.findById(invitationId)
                .filter(found -> found.getWorkspaceId().equals(workspaceId))
                .filter(found -> !found.isAccepted() && !found.isRevoked())
                .orElseThrow(InvitationNotFoundException::new);

        invitation.revoke();
        log.info("[초대 취소] invitationId={} workspaceId={}", invitationId, workspaceId);
    }

    /** 로그인 전 초대 링크 화면이 부르는 미리보기. 토큰을 가진 사람만 도달한다. */
    public InvitationPreviewResponse preview(String token) {
        WorkspaceInvitation invitation = pendingByToken(token);
        Workspace workspace = activeWorkspace(invitation.getWorkspaceId());
        String inviterName = userRepository.findById(invitation.getInvitedBy())
                .map(User::getDisplayName)
                .orElse("알 수 없음");

        return new InvitationPreviewResponse(
                workspace.getId(),
                workspace.getName(),
                invitation.getEmail(),
                invitation.getRole().name(),
                inviterName,
                invitation.getExpiresAt()
        );
    }

    @Transactional
    public InvitationAcceptResponse accept(String userId, String token) {
        WorkspaceInvitation invitation = invitationRepository.findByTokenHash(OpaqueTokens.sha256(token))
                .filter(found -> !found.isRevoked())
                .orElseThrow(InvitationNotFoundException::new);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        // 이 검증이 수락 계정을 확정한다. 링크가 유출돼도 초대받은 주소의 계정만 들어올 수 있다.
        if (!user.getEmail().equalsIgnoreCase(invitation.getEmail())) {
            throw new InvitationEmailMismatchException(invitation.getEmail());
        }

        Workspace workspace = activeWorkspace(invitation.getWorkspaceId());
        boolean alreadyMember = workspaceMemberRepository
                .findActiveRole(invitation.getWorkspaceId(), userId)
                .isPresent();

        // 링크를 두 번 눌러도 같은 결과가 되도록, 이미 멤버면 성공으로 돌려준다.
        if (invitation.isAccepted() && !alreadyMember) {
            throw new InvitationAlreadyAcceptedException();
        }
        if (invitation.isExpired() && !alreadyMember) {
            throw new InvitationExpiredException();
        }

        if (!alreadyMember) {
            workspaceMemberRepository.save(new WorkspaceMember(
                    workspace, userRepository.getReferenceById(userId), invitation.getRole()));
            // 수락 전 조회로 캐시된 NONE 판정이 남아 있으면 document-svc가 계속 거부한다.
            authzProjectionStore.evict(workspace.getId(), userId);
        }
        if (!invitation.isAccepted()) {
            invitation.accept(userId);
        }
        log.info("[초대 수락] invitationId={} workspaceId={} userId={}",
                invitation.getId(), workspace.getId(), userId);

        return new InvitationAcceptResponse(
                workspace.getId(), workspace.getName(), invitation.getRole().name());
    }

    private WorkspaceInvitation pendingByToken(String token) {
        WorkspaceInvitation invitation = invitationRepository.findByTokenHash(OpaqueTokens.sha256(token))
                .filter(found -> !found.isRevoked())
                .orElseThrow(InvitationNotFoundException::new);
        if (invitation.isAccepted()) {
            throw new InvitationAlreadyAcceptedException();
        }
        if (invitation.isExpired()) {
            throw new InvitationExpiredException();
        }
        return invitation;
    }

    /** 같은 이메일의 계정 중 하나라도 이미 멤버면 초대할 필요가 없다. */
    private boolean hasMemberAccount(String workspaceId, String email) {
        return userRepository.findAllByEmail(email).stream()
                .anyMatch(user -> workspaceMemberRepository.findActiveRole(workspaceId, user.getId()).isPresent());
    }

    private void requireOwner(String workspaceId, String actorId) {
        WorkspaceRole role = workspaceMemberRepository.findActiveRole(workspaceId, actorId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (role != WorkspaceRole.OWNER) {
            throw new WorkspaceAccessDeniedException("멤버를 초대하려면 OWNER 권한이 필요합니다.");
        }
    }

    private Workspace activeWorkspace(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .filter(workspace -> workspace.getDeletedAt() == null)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }
}
