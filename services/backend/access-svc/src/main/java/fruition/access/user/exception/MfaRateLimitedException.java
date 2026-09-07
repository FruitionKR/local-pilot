package fruition.access.user.exception;

public class MfaRateLimitedException extends RuntimeException {
    private final long retryAfter;

    public MfaRateLimitedException(long retryAfter) {
        super("MFA 검증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() { return retryAfter; }
}
