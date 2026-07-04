package fruition.security.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthExchangeCodeStoreTest {

    @Test
    void issueThenConsume_returnsUserId() {
        OAuthExchangeCodeStore store = new OAuthExchangeCodeStore();

        String code = store.issue("user_1f9a74af");

        assertThat(store.consume(code)).contains("user_1f9a74af");
    }

    @Test
    void consume_isOneTimeUse() {
        OAuthExchangeCodeStore store = new OAuthExchangeCodeStore();
        String code = store.issue("user_1f9a74af");

        store.consume(code);

        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void consume_unknownCode_returnsEmpty() {
        OAuthExchangeCodeStore store = new OAuthExchangeCodeStore();

        assertThat(store.consume("unknown-code")).isEmpty();
    }
}
