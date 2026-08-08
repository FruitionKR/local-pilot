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
        String token = signer.issue("ws_1", "user_1", "hash-1", "{\"publish_allowed\":true}");

        assertThat(signer.verify(token, "ws_1", "user_1", "hash-1")).contains("publish_allowed");
        assertThatThrownBy(() -> signer.verify(token, "ws_1", "user_1", "hash-2"))
                .isInstanceOf(InvalidSkillRequestException.class);
    }

    @Test
    void tokenCannotBeReusedByAnotherUserOrWorkspace() {
        String token = signer.issue("ws_1", "user_1", "hash-1", "{}");

        assertThatThrownBy(() -> signer.verify(token, "ws_2", "user_1", "hash-1"))
                .isInstanceOf(InvalidSkillRequestException.class);
        assertThatThrownBy(() -> signer.verify(token, "ws_1", "user_2", "hash-1"))
                .isInstanceOf(InvalidSkillRequestException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = signer.issue("ws_1", "user_1", "hash-1", "{}");

        assertThatThrownBy(() -> signer.verify(token + "x", "ws_1", "user_1", "hash-1"))
                .isInstanceOf(InvalidSkillRequestException.class);
    }
}
