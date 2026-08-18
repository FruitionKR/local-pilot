package fruition.access.user.exception;

public class EmailAvailabilityRateLimitedException extends RuntimeException {
    private final long retryAfter;

    public EmailAvailabilityRateLimitedException(long retryAfter) {
        super("이메일 중복 확인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
