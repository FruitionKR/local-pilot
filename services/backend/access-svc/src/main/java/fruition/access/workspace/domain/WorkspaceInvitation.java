package fruition.access.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 이메일 주소로 보내는 워크스페이스 초대. 계정이 {@code (email, provider)}로 분리돼 있어
 * 이메일만으로는 계정을 특정할 수 없으므로, 어느 계정이 멤버가 될지는 수락 시점에 정해진다.
 */
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitation {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private String workspaceId;

    @Column(nullable = false, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by", nullable = false)
    private String invitedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by")
    private String acceptedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WorkspaceInvitation() {}

    public WorkspaceInvitation(String id, String workspaceId, String email, WorkspaceRole role,
                               String tokenHash, Instant expiresAt, String invitedBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.invitedBy = invitedBy;
        this.createdAt = Instant.now();
    }

    /** 재초대. 새 행을 만들지 않고 토큰과 만료를 갈아끼워 링크 하나만 유효하게 유지한다. */
    public void reissue(String tokenHash, Instant expiresAt, WorkspaceRole role, String invitedBy) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.role = role;
        this.invitedBy = invitedBy;
    }

    public void accept(String userId) {
        this.acceptedAt = Instant.now();
        this.acceptedBy = userId;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getEmail() { return email; }
    public WorkspaceRole getRole() { return role; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getInvitedBy() { return invitedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
