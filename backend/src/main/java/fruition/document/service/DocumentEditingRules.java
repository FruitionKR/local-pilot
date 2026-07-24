package fruition.document.service;

import fruition.document.exception.InvalidDocumentFilenameException;
import fruition.document.exception.InvalidMarkdownContentException;
import fruition.document.exception.MarkdownContentTooLargeException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

final class DocumentEditingRules {

    static final int MAX_MARKDOWN_BYTES = 5 * 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 255;

    private DocumentEditingRules() {
    }

    static Filename rename(String displayName, String currentFilename) {
        String normalizedDisplayName = normalizeDisplayName(displayName);
        String extension = extensionOf(currentFilename);
        String filename = normalizedDisplayName + extension;
        if (filename.length() > MAX_FILENAME_LENGTH) {
            throw new InvalidDocumentFilenameException("문서 이름은 확장자를 포함해 255자 이하여야 합니다.");
        }
        return new Filename(normalizedDisplayName, filename, filename.toLowerCase(Locale.ROOT));
    }

    static MarkdownContent markdown(String markdown) {
        if (markdown == null) {
            throw new InvalidMarkdownContentException("Markdown 본문은 null일 수 없습니다.");
        }
        return markdown(markdown, markdown.getBytes(StandardCharsets.UTF_8));
    }

    static MarkdownContent markdown(byte[] bytes) {
        if (bytes.length > MAX_MARKDOWN_BYTES) {
            throw new MarkdownContentTooLargeException("Markdown 본문은 UTF-8 기준 5MB 이하여야 합니다.");
        }
        try {
            String markdown = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return markdown(markdown, bytes);
        } catch (CharacterCodingException exception) {
            throw new InvalidMarkdownContentException("Markdown 본문은 올바른 UTF-8이어야 합니다.");
        }
    }

    private static MarkdownContent markdown(String markdown, byte[] bytes) {
        if (bytes.length > MAX_MARKDOWN_BYTES) {
            throw new MarkdownContentTooLargeException("Markdown 본문은 UTF-8 기준 5MB 이하여야 합니다.");
        }
        return new MarkdownContent(markdown, bytes, sha256(bytes));
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new InvalidDocumentFilenameException("문서 이름은 필수입니다.");
        }
        String normalized = displayName.trim();
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..")) {
            throw new InvalidDocumentFilenameException("문서 이름으로 빈 이름, '.', '..'을 사용할 수 없습니다.");
        }
        if (normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidDocumentFilenameException("문서 이름에 허용되지 않는 문자가 포함되어 있습니다.");
        }
        return normalized;
    }

    private static String extensionOf(String filename) {
        int extensionIndex = filename.lastIndexOf('.');
        return extensionIndex > 0 ? filename.substring(extensionIndex) : "";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    record Filename(String displayName, String filename, String normalizedFilename) {
    }

    record MarkdownContent(String markdown, byte[] bytes, String contentHash) {
        boolean hasSameContent(String currentContentHash) {
            return Objects.equals(contentHash, currentContentHash);
        }
    }
}
