package fruition.workspace.domain;

import fruition.user.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "workspace_members")
public class WorkspaceMember {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";

    @EmbeddedId
    private WorkspaceMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(name = "workspace_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false)
    private String role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected WorkspaceMember() {}

    public WorkspaceMember(Workspace workspace, User user, String role) {
        this.id = new WorkspaceMemberId(workspace.getId(), user.getId());
        this.workspace = workspace;
        this.user = user;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public String getWorkspaceId() { return id.getWorkspaceId(); }
    public String getUserId() { return id.getUserId(); }
    public String getRole() { return role; }
    public Instant getJoinedAt() { return joinedAt; }
}
