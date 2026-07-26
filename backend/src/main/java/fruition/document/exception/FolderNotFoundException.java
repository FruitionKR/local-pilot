package fruition.document.exception;

import java.util.UUID;

public class FolderNotFoundException extends RuntimeException {
    public FolderNotFoundException(UUID folderId) {
        super("폴더를 찾을 수 없습니다: id=" + folderId);
    }
}
