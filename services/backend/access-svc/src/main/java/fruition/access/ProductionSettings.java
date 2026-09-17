package fruition.access;

import java.net.URI;
import java.util.Base64;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** 운영에서 메일 stub나 localhost 초대 링크로 기동하지 않도록 설정을 검증한다. */
@Configuration
@Profile("production")
public class ProductionSettings {

    public ProductionSettings(Environment environment) {
        for (String key : new String[] {"spring.mail.host", "spring.mail.username", "spring.mail.password",
                "app.auth.email-verification.from", "app.auth.mfa.encryption-key",
                "app.workspace.invitation.accept-url"}) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank() || value.contains("REPLACE_ME")) {
                throw new IllegalArgumentException("필수 운영 설정 누락: " + key);
            }
        }
        String key = environment.getRequiredProperty("app.auth.mfa.encryption-key");
        if (Base64.getDecoder().decode(key).length != 32) {
            throw new IllegalArgumentException("MFA 키는 base64로 인코딩한 32바이트여야 합니다.");
        }
        URI invitation = URI.create(environment.getRequiredProperty("app.workspace.invitation.accept-url"));
        if (!"https".equals(invitation.getScheme()) || invitation.getHost() == null
                || invitation.getHost().equals("localhost") || invitation.getHost().equals("127.0.0.1")) {
            throw new IllegalArgumentException("초대 URL은 공개 HTTPS 주소여야 합니다.");
        }
        int port = environment.getProperty("spring.mail.port", Integer.class, 0);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("필수 운영 설정 오류: spring.mail.port");
        }
        if (!environment.getProperty("app.auth.email-verification.dev-fixed-code", "").isBlank()) {
            throw new IllegalArgumentException("운영에서는 고정 이메일 인증 코드를 사용할 수 없습니다.");
        }
    }
}
