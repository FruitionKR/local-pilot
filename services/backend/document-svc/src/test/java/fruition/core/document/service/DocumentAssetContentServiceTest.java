package fruition.core.document.service;

import fruition.core.document.dto.DocumentContentSaveResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAssetContentServiceTest {

    @Mock DocumentAssetSaveRequestParser requestParser;
    @Mock DocumentAssetValidator assetValidator;
    @Mock DocumentAssetStorageCoordinator storageCoordinator;
    @Mock DocumentService documentService;

    @Test
    void save_replacesPlaceholderAndReturnsServerMarkdownAndMapping() {
        UUID attachmentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{1});
        var parsed = new DocumentAssetSaveRequestParser.ParsedAssetSaveRequest(
                "![](attachment://" + attachmentId + ")", 3, Map.of(attachmentId, file));
        var validated = validated();
        var stored = new DocumentAssetStorageCoordinator.StoredAsset(
                assetId, "assets/ws_1/" + assetId + "/content", validated);
        when(requestParser.parse(eq("metadata"), any())).thenReturn(parsed);
        when(assetValidator.validateAll(Map.of(attachmentId, file)))
                .thenReturn(Map.of(attachmentId, validated));
        when(storageCoordinator.storeAll("ws_1", Map.of(attachmentId, validated)))
                .thenReturn(Map.of(attachmentId, stored));
        when(documentService.saveContentWithAssets(
                eq("ws_1"), eq("user_1"), eq("doc_1"), any(), eq(3L), any(), eq("operation_1")))
                .thenAnswer(invocation -> new DocumentContentSaveResponse(
                        "doc_1", 4, "a".repeat(64), Instant.now(), true,
                        invocation.getArgument(3), List.of()));

        DocumentAssetContentService service = service();
        DocumentContentSaveResponse response = service.save(
                "ws_1", "user_1", "doc_1", "metadata", new LinkedMultiValueMap<>(),
                "operation_1");

        String expectedPath = "/api/workspaces/ws_1/assets/" + assetId + "/content";
        assertThat(response.markdown()).isEqualTo("![](" + expectedPath + ")");
        assertThat(response.attachments()).singleElement().satisfies(mapping -> {
            assertThat(mapping.attachmentId()).isEqualTo(attachmentId);
            assertThat(mapping.assetId()).isEqualTo(assetId);
            assertThat(mapping.contentPath()).isEqualTo(expectedPath);
        });
        verify(storageCoordinator, never()).compensate(any());
    }

    @Test
    void save_whenDatabaseConflicts_compensatesStoredObjects() {
        UUID attachmentId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[]{1});
        var parsed = new DocumentAssetSaveRequestParser.ParsedAssetSaveRequest(
                "![](attachment://" + attachmentId + ")", 3, Map.of(attachmentId, file));
        var validated = validated();
        var stored = new DocumentAssetStorageCoordinator.StoredAsset(
                assetId, "assets/ws_1/" + assetId + "/content", validated);
        when(requestParser.parse(eq("metadata"), any())).thenReturn(parsed);
        when(assetValidator.validateAll(Map.of(attachmentId, file)))
                .thenReturn(Map.of(attachmentId, validated));
        when(storageCoordinator.storeAll("ws_1", Map.of(attachmentId, validated)))
                .thenReturn(Map.of(attachmentId, stored));
        when(documentService.saveContentWithAssets(
                any(), any(), any(), any(), any(Long.class), any(), any()))
                .thenThrow(new DocumentVersionConflictException("충돌"));

        assertThatThrownBy(() -> service().save(
                "ws_1", "user_1", "doc_1", "metadata", new LinkedMultiValueMap<>(), null))
                .isInstanceOf(DocumentVersionConflictException.class);

        ArgumentCaptor<java.util.Collection<DocumentAssetStorageCoordinator.StoredAsset>> compensated =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(storageCoordinator).compensate(compensated.capture());
        assertThat(compensated.getValue()).containsExactly(stored);
    }

    private DocumentAssetContentService service() {
        return new DocumentAssetContentService(
                requestParser, assetValidator, storageCoordinator, documentService);
    }

    private DocumentAssetValidator.ValidatedAsset validated() {
        return new DocumentAssetValidator.ValidatedAsset(
                "image.png", "image/png", new byte[]{1}, 1, 1, "a".repeat(64));
    }
}
