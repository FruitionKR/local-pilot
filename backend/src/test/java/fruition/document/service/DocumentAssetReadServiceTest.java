package fruition.document.service;

import fruition.document.domain.DocumentAsset;
import fruition.document.exception.DocumentAssetNotFoundException;
import fruition.document.repository.DocumentAssetRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetReadServiceTest {

    @Mock WorkspaceMemberRepository memberRepository;
    @Mock DocumentAssetRepository assetRepository;
    @Mock DocumentAssetObjectStorage objectStorage;

    @Test
    void readMetadata_memberReceivesVerifiedMetadataWithoutTouchingStorage() {
        UUID assetId = UUID.randomUUID();
        DocumentAsset asset = asset(assetId, "ws_1");
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.of(asset));

        var result = service().readMetadata("ws_1", "user_1", assetId);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.contentLength()).isEqualTo(3);
        assertThat(result.etag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(result.storageKey()).isEqualTo("assets/ws_1/content");
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void openStream_readsObjectForStoredKey() {
        UUID assetId = UUID.randomUUID();
        DocumentAsset asset = asset(assetId, "ws_1");
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.of(asset));
        when(objectStorage.get("assets/ws_1/content")).thenReturn(stream);

        DocumentAssetReadService service = service();

        assertThat(service.openStream(service.readMetadata("ws_1", "user_1", assetId))).isSameAs(stream);
    }

    @Test
    void readMetadata_nonMemberReturnsNotFoundWithoutLookingUpAsset() {
        UUID assetId = UUID.randomUUID();
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "intruder")).thenReturn(false);

        assertThatThrownBy(() -> service().readMetadata("ws_1", "intruder", assetId))
                .isInstanceOf(DocumentAssetNotFoundException.class);
        verify(assetRepository, never()).findByIdAndWorkspaceId(assetId, "ws_1");
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void readMetadata_assetFromAnotherWorkspaceReturnsNotFound() {
        UUID assetId = UUID.randomUUID();
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(assetRepository.findByIdAndWorkspaceId(assetId, "ws_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().readMetadata("ws_1", "user_1", assetId))
                .isInstanceOf(DocumentAssetNotFoundException.class);
        verify(objectStorage, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    private DocumentAssetReadService service() {
        return new DocumentAssetReadService(memberRepository, assetRepository, objectStorage);
    }

    private DocumentAsset asset(UUID id, String workspaceId) {
        return new DocumentAsset(id, workspaceId, "user_1", "image.png", "image/png", 3,
                1, 1, "a".repeat(64), "assets/ws_1/content", Instant.now());
    }
}
