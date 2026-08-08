package fruition.skill.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skills")
public class Skill {
    @Id private String id;
    @Column(name = "workspace_id") private String workspaceId;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false) private SkillScope scope;
    @Column(name = "owner_user_id") private String ownerUserId;
    @Column(nullable = false, length = 63) private String command;
    @Column(name = "auto_routing_enabled", nullable = false) private boolean autoRoutingEnabled;
    @Column(name = "deleted_at") private Instant deletedAt;
    @Column(name = "deleted_by") private String deletedBy;
    @Column(name = "created_by", nullable = false, updatable = false) private String createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Skill() {}

    public Skill(String workspaceId, String userId, SkillScope scope, String command) {
        this.id = UUID.randomUUID().toString();
        this.workspaceId = workspaceId;
        this.createdBy = userId;
        this.autoRoutingEnabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        changeIdentity(scope, command, userId, workspaceId);
    }

    public void changeIdentity(SkillScope scope, String command, String requesterId, String currentWorkspaceId) {
        this.scope = scope;
        this.ownerUserId = scope == SkillScope.personal ? requesterId : null;
        this.workspaceId = scope == SkillScope.team ? currentWorkspaceId : null;
        this.command = command;
        this.updatedAt = Instant.now();
    }

    public void setAutoRoutingEnabled(boolean enabled) {
        this.autoRoutingEnabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void delete(String userId) {
        this.deletedAt = Instant.now();
        this.deletedBy = userId;
        this.updatedAt = deletedAt;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public SkillScope getScope() { return scope; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getCommand() { return command; }
    public boolean isAutoRoutingEnabled() { return autoRoutingEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
