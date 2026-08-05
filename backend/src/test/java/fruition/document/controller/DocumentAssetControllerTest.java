package fruition.document.controller;

import fruition.document.service.DocumentAssetReadService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAssetControllerTest {

    private final DocumentAssetReadService readService = mock(DocumentAssetReadService.class);
    private final DocumentAssetController controller = new DocumentAssetController(readService);

    @Test
    void getContent_setsPrivateSecurityAndEntityHeaders() {
        UUID assetId = UUID.randomUUID();
        when(readService.read("ws_1", "user_1", assetId)).thenReturn(
                new DocumentAssetReadService.AssetContent(
                        "image/png", 3, "\"hash\"", new ByteArrayInputStream(new byte[]{1, 2, 3})));

        var response = controller.getContent("ws_1", "user_1", assetId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"hash\"");
        assertThat(response.getHeaders().getCacheControl()).contains("private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isInstanceOf(InputStreamResource.class);
    }

    @Test
    void getContent_matchingEtagReturnsNotModifiedWithoutBody() {
        UUID assetId = UUID.randomUUID();
        when(readService.read("ws_1", "user_1", assetId)).thenReturn(
                new DocumentAssetReadService.AssetContent(
                        "image/png", 3, "\"hash\"", new ByteArrayInputStream(new byte[]{1, 2, 3})));

        var response = controller.getContent("ws_1", "user_1", assetId, "\"hash\"");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"hash\"");
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("private");
    }
}
