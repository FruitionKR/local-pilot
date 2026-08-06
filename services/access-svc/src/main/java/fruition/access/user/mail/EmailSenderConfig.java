package fruition.access.user.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 인증번호 발송 sender 등록. spring.mail.host가 설정돼 있으면 SMTP 발송, 없으면 dev 로그 stub를 배타 등록한다.
 * 운영 배포는 SPRING_MAIL_HOST(+계정)와 MAIL_FROM을 주입하면 자동으로 SMTP로 전환된다.
 */
@Configuration
public class EmailSenderConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.mail.host")
    EmailVerificationSender smtpEmailVerificationSender(
            JavaMailSender mailSender,
            @Value("${app.auth.email-verification.from:}") String from) {
        return new SmtpEmailVerificationSender(mailSender, from);
    }

    @Bean
    @ConditionalOnMissingBean(EmailVerificationSender.class)
    EmailVerificationSender loggingEmailVerificationSender() {
        return new LoggingEmailVerificationSender();
    }
}
