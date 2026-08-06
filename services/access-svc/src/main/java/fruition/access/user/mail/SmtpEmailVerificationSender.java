package fruition.access.user.mail;

import fruition.access.user.exception.EmailVerificationSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 운영용 SMTP 발송. 어떤 provider든 SMTP 계정만 있으면 동작한다(SES/SendGrid/Gmail SMTP 등).
 * 인증번호와 수신 이메일 원문은 로그에 남기지 않는다.
 */
public class SmtpEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailVerificationSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailVerificationSender(JavaMailSender mailSender, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "SMTP 발송에는 발신 주소(app.auth.email-verification.from / MAIL_FROM)가 필요합니다.");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String email, String purpose, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(subjectFor(purpose));
        message.setText(bodyFor(purpose, code));
        try {
            mailSender.send(message);
            log.info("[인증번호 발송 완료] purpose={}", purpose);
        } catch (MailException e) {
            log.warn("[인증번호 발송 실패] purpose={} error={}", purpose, e.getMessage());
            throw new EmailVerificationSendException("인증번호 메일 발송에 실패했습니다.", e);
        }
    }

    private String subjectFor(String purpose) {
        return switch (purpose) {
            case "signup" -> "[Fruition] 회원가입 인증번호";
            case "password_reset" -> "[Fruition] 비밀번호 재설정 인증번호";
            default -> "[Fruition] 인증번호";
        };
    }

    private String bodyFor(String purpose, String code) {
        String action = "password_reset".equals(purpose) ? "비밀번호 재설정" : "회원가입";
        return "Fruition " + action + " 인증번호는 아래와 같습니다.\n\n"
                + "인증번호: " + code + "\n\n"
                + "인증번호를 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.";
    }
}
