package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceIcon;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceIconUpdateRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.exception.UnsupportedWorkspaceIconException;
import fruition.access.workspace.exception.WorkspaceIconNotFoundException;
import fruition.access.workspace.exception.WorkspaceIconTooLargeException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceIconRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceIconServiceTest {

    private static final String WORKSPACE_ID = "ws_aaa11111";
    private static final String OWNER_ID = "user_owner";
    private static final String MEMBER_ID = "user_member";

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock WorkspaceIconRepository workspaceIconRepository;

    WorkspaceIconService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceIconService(
                workspaceMemberRepository, workspaceIconRepository, new WorkspaceIconValidator());
    }

    private Workspace workspace() {
        return new Workspace(WORKSPACE_ID, "워크스페이스");
    }

    private void owned(Workspace workspace) {
        when(workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                WORKSPACE_ID, OWNER_ID, WorkspaceRole.OWNER)).thenReturn(Optional.of(workspace));
    }

    private MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "icon.png", "image/png", bytes);
    }

    @Test
    void updateIcon_setsEmoji() {
        Workspace workspace = workspace();
        owned(workspace);

        WorkspaceResponse response = service.updateIcon(
                OWNER_ID, WORKSPACE_ID, new WorkspaceIconUpdateRequest("📁"));

        assertThat(response.iconEmoji()).isEqualTo("📁");
        assertThat(response.iconUrl()).isNull();
        verify(workspaceIconRepository, never()).deleteById(any());
    }

    @Test
    void updateIcon_nullClearsEmojiAndImage() {
        Workspace workspace = workspace();
        workspace.changeIconImage("image/png", "hash");
        owned(workspace);

        WorkspaceResponse response = service.updateIcon(
                OWNER_ID, WORKSPACE_ID, new WorkspaceIconUpdateRequest(null));

        assertThat(response.iconEmoji()).isNull();
        assertThat(response.iconUrl()).isNull();
        // 이미지가 있었으면 바이너리도 함께 지운다.
        verify(workspaceIconRepository).deleteById(WORKSPACE_ID);
    }

    @Test
    void updateIcon_notOwnedThrows() {
        when(workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                WORKSPACE_ID, OWNER_ID, WorkspaceRole.OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateIcon(
                OWNER_ID, WORKSPACE_ID, new WorkspaceIconUpdateRequest("📁")))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void updateIcon_deletedWorkspaceThrows() {
        Workspace workspace = workspace();
        workspace.softDelete(OWNER_ID, Instant.now());
        owned(workspace);

        assertThatThrownBy(() -> service.updateIcon(
                OWNER_ID, WORKSPACE_ID, new WorkspaceIconUpdateRequest("📁")))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void updateIconImage_storesBinaryAndClearsEmoji() {
        Workspace workspace = workspace();
        workspace.changeIcon("📁");
        owned(workspace);
        when(workspaceIconRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        WorkspaceResponse response = service.updateIconImage(OWNER_ID, WORKSPACE_ID, upload(PNG));

        assertThat(response.iconEmoji()).isNull();
        assertThat(response.iconUrl()).isEqualTo("/api/workspaces/" + WORKSPACE_ID + "/icon/image");
        verify(workspaceIconRepository).save(any(WorkspaceIcon.class));
        assertThat(workspace.getIconImageContentType()).isEqualTo("image/png");
    }

    @Test
    void updateIconImage_replacesExistingBinary() {
        Workspace workspace = workspace();
        owned(workspace);
        WorkspaceIcon existing = new WorkspaceIcon(WORKSPACE_ID, new byte[]{9});
        when(workspaceIconRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(existing));

        service.updateIconImage(OWNER_ID, WORKSPACE_ID, upload(JPEG));

        // 새 row를 만들지 않고 기존 row를 덮어써 고아 바이너리가 남지 않는다.
        verify(workspaceIconRepository, never()).save(any());
        assertThat(existing.getImage()).isEqualTo(JPEG);
        assertThat(workspace.getIconImageContentType()).isEqualTo("image/jpeg");
    }

    /** 선언된 Content-Type은 클라이언트가 정하므로 매직 바이트로 판별한다. */
    @Test
    void updateIconImage_rejectsNonImageDeclaredAsPng() {
        owned(workspace());

        assertThatThrownBy(() -> service.updateIconImage(
                OWNER_ID, WORKSPACE_ID, upload("<html>not an image</html>".getBytes())))
                .isInstanceOf(UnsupportedWorkspaceIconException.class);
        verify(workspaceIconRepository, never()).save(any());
    }

    @Test
    void updateIconImage_rejectsOversizedFile() {
        owned(workspace());
        byte[] tooLarge = new byte[(int) WorkspaceIconValidator.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, tooLarge, 0, PNG.length);

        assertThatThrownBy(() -> service.updateIconImage(OWNER_ID, WORKSPACE_ID, upload(tooLarge)))
                .isInstanceOf(WorkspaceIconTooLargeException.class);
        verify(workspaceIconRepository, never()).save(any());
    }

    @Test
    void readIconImage_memberGetsBytes() {
        Workspace workspace = workspace();
        workspace.changeIconImage("image/png", "hash-1");
        when(workspaceMemberRepository.findActiveWorkspaceForMember(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(workspace));
        when(workspaceIconRepository.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(new WorkspaceIcon(WORKSPACE_ID, PNG)));

        WorkspaceIconService.IconImage icon = service.readIconImage(MEMBER_ID, WORKSPACE_ID);

        assertThat(icon.bytes()).isEqualTo(PNG);
        assertThat(icon.contentType()).isEqualTo("image/png");
        assertThat(icon.hash()).isEqualTo("hash-1");
    }

    @Test
    void readIconImage_nonMemberGets404() {
        when(workspaceMemberRepository.findActiveWorkspaceForMember(WORKSPACE_ID, "user_stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readIconImage("user_stranger", WORKSPACE_ID))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void readIconImage_emojiOnlyWorkspaceGets404() {
        Workspace workspace = workspace();
        workspace.changeIcon("📁");
        when(workspaceMemberRepository.findActiveWorkspaceForMember(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.readIconImage(MEMBER_ID, WORKSPACE_ID))
                .isInstanceOf(WorkspaceIconNotFoundException.class);
    }
}
