package fruition.workspace.domain;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class WorkspaceMemberId implements Serializable {

    private String workspaceId;
    private String userId;

    protected WorkspaceMemberId() {}

    public WorkspaceMemberId(String workspaceId, String userId) {
        this.workspaceId = workspaceId;
        this.userId = userId;
    }

    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkspaceMemberId that)) return false;
        return Objects.equals(workspaceId, that.workspaceId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, userId);
    }
}
