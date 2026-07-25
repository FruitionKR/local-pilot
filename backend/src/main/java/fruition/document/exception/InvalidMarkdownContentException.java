package fruition.document.exception;

public class InvalidMarkdownContentException extends RuntimeException {
    public InvalidMarkdownContentException(String message) {
        super(message);
    }
}
