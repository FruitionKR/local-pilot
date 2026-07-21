package fruition.user.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 개발용 stub. 실제 메일을 보내지 않고 인증번호를 로그로 출력한다.
 * 운영 환경에서는 실제 발송 sender로 교체해야 한다(로그에 코드가 남으므로 dev 전용).
 */
@Component
public class LoggingEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailVerificationSender.class);

    @Override
    public void send(String email, String purpose, String code) {
        log.info("[인증번호 발송(dev stub)] email={} purpose={} code={}", email, purpose, code);
    }
}
