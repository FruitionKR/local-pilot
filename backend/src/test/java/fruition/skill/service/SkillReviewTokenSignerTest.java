package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.skill.exception.InvalidSkillRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillReviewTokenSignerTest {
    private final SkillReviewTokenSigner signer = new SkillReviewTokenSigner(new ObjectMapper(), "test-secret");

    @Test
    void tokenAcceptsOnlyReviewedDefinitionHash() {
        String token = signer.issue("hash-1", "{\"publish_allowed\":true}");

        assertThat(signer.verify(token, "hash-1")).contains("publish_allowed");
        assertThatThrownBy(() -> signer.verify(token, "hash-2"))
                .isInstanceOf(InvalidSkillRequestException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = signer.issue("hash-1", "{}");

        assertThatThrownBy(() -> signer.verify(token + "x", "hash-1"))
                .isInstanceOf(InvalidSkillRequestException.class);
    }
}
