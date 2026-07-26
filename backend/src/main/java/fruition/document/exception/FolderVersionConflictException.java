package fruition.document.exception;

public class FolderVersionConflictException extends RuntimeException {
    public FolderVersionConflictException(String message) {
        super(message);
    }
}
