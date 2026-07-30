package fruition.document.exception;

public class MarkdownDiffTooLargeException extends RuntimeException {
    public MarkdownDiffTooLargeException(String message) {
        super(message);
    }
}
