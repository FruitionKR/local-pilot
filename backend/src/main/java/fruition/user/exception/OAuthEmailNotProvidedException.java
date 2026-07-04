package fruition.user.exception;

public class OAuthEmailNotProvidedException extends RuntimeException {
    public OAuthEmailNotProvidedException(String provider) {
        super(provider + " 계정에서 이메일 정보를 가져올 수 없습니다.");
    }
}
