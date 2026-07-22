package fruition.user.mail;

/**
 * 인증번호 발송 추상화. MVP는 로그 stub({@link LoggingEmailVerificationSender})만 제공하며,
 * 운영 배포 전 실제 메일(SMTP) 발송 구현으로 교체한다.
 */
public interface EmailVerificationSender {
    void send(String email, String purpose, String code);
}
