package fruition.user.exception;

public class VerificationRateLimitedException extends RuntimeException {
    private final long retryAfter;

    public VerificationRateLimitedException(long retryAfter) {
        super("인증 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
