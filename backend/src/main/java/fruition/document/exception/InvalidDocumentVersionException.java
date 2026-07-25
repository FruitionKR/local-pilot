package fruition.document.exception;

public class InvalidDocumentVersionException extends RuntimeException {
    public InvalidDocumentVersionException(String message) {
        super(message);
    }
}
