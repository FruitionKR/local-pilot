package fruition.access.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 아이콘 이미지 바이너리. 워크스페이스와 1:1이며 workspace 삭제 시 함께 지워진다.
 *
 * <p>{@link Workspace}와 분리한 이유: 목록 조회가 워크스페이스마다 최대 1MB를 함께 읽지 않게 한다.
 */
@Entity
@Table(name = "workspace_icons")
public class WorkspaceIcon {

    @Id
    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(nullable = false)
    private byte[] image;

    protected WorkspaceIcon() {}

    public WorkspaceIcon(String workspaceId, byte[] image) {
        this.workspaceId = workspaceId;
        this.image = image;
    }

    public void replace(byte[] image) {
        this.image = image;
    }

    public String getWorkspaceId() { return workspaceId; }
    public byte[] getImage() { return image; }
}
