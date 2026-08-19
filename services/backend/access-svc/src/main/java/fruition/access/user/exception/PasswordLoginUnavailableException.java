package fruition.access.user.exception;

public class PasswordLoginUnavailableException extends RuntimeException {
    public PasswordLoginUnavailableException(String provider) {
        super("이 이메일은 " + provider + " 로그인으로 가입되어 있어 비밀번호를 설정할 수 없습니다.");
    }
}
