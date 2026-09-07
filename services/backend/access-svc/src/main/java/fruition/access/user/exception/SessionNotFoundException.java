package fruition.access.user.exception;

/** 남의 세션이거나 이미 폐기된 세션. 존재를 드러내지 않도록 둘 다 404로 답한다. */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(Long sessionId) {
        super("세션을 찾을 수 없습니다: id=" + sessionId);
    }
}
