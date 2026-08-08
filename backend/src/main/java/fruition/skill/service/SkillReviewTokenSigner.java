package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.skill.exception.InvalidSkillRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Component
public class SkillReviewTokenSigner {
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public SkillReviewTokenSigner(ObjectMapper objectMapper,
                                  @Value("${app.skill.review-token-secret:${app.skill.agent-token}}") String secret) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String definitionHash, String safetyResult) {
        try {
            Payload payload = new Payload(definitionHash, safetyResult, Instant.now().plusSeconds(600).getEpochSecond());
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            return encoded + "." + sign(encoded);
        } catch (Exception exception) {
            throw new IllegalStateException("Skill 검토 토큰을 생성할 수 없습니다.", exception);
        }
    }

    public String verify(String token, String expectedHash) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) {
                throw invalid();
            }
            Payload payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), Payload.class);
            if (!payload.definitionHash().equals(expectedHash)
                    || payload.expiresAt() < Instant.now().getEpochSecond()) {
                throw invalid();
            }
            return payload.safetyResult();
        } catch (InvalidSkillRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private InvalidSkillRequestException invalid() {
        return new InvalidSkillRequestException("검토 토큰이 만료되었거나 Skill 내용과 일치하지 않습니다.");
    }

    private record Payload(String definitionHash, String safetyResult, long expiresAt) {}
}
