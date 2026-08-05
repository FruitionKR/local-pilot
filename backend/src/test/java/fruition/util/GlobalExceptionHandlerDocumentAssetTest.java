package fruition.util;

import fruition.document.exception.DocumentAssetTooLargeException;
import fruition.document.exception.MarkdownContentTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerDocumentAssetTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void payloadLimits_useDistinctErrorCodes() {
        var markdown = handler.handleMarkdownContentTooLarge(
                new MarkdownContentTooLargeException("Markdown 5MB 초과"));
        var assets = handler.handleDocumentAssetTooLarge(
                new DocumentAssetTooLargeException("이미지 100MB 초과"));

        assertThat(markdown.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(markdown.getBody().error().code()).isEqualTo("MARKDOWN_CONTENT_TOO_LARGE");
        assertThat(assets.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(assets.getBody().error().code()).isEqualTo("DOCUMENT_ASSET_TOO_LARGE");
    }
}
