package fruition.user.mail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개발용 stub. 실제 메일을 보내지 않고 인증번호를 로그로 출력한다.
 * SMTP(spring.mail.host)가 설정되지 않은 환경에서만 {@link EmailSenderConfig}가 fallback으로 등록한다.
 */
public class LoggingEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailVerificationSender.class);

    // 이 stub이 활성화된 채로 배포되면 인증번호가 로그로 노출되므로, 부팅 시점에 눈에 띄게 경고한다.
    @PostConstruct
    void warnDevStubActive() {
        log.warn("[보안 경고] EmailVerificationSender가 dev stub(LoggingEmailVerificationSender)으로 활성화됨 "
                + "— 인증번호가 로그로 노출됩니다. 운영 배포 전 실제 메일 발송 구현으로 교체하세요.");
    }

    @Override
    public void send(String email, String purpose, String code) {
        log.info("[인증번호 발송(dev stub)] email={} purpose={} code={}", email, purpose, code);
    }
}
