package fruition.core.aihistory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 편집 적용 표. {@code source=agent} 문자열은 클라이언트가 임의로 넣을 수 있으므로
 * Backend가 발급한 값을 대조해야 로그가 오염되지 않는다.
 */
class AgentApplyOperationStoreTest {

    private final AgentApplyOperationStore store = new AgentApplyOperationStore();

    @Test
    @DisplayName("발급한 표는 같은 사용자·문서에서 한 번 소비된다")
    void issuedTokenIsConsumedOnce() {
        String token = store.issue("user_1", "doc_1");

        assertThat(store.consume(token, "user_1", "doc_1")).isTrue();
        // 같은 표로 두 번 기록되지 않는다.
        assertThat(store.consume(token, "user_1", "doc_1")).isFalse();
    }

    @Test
    @DisplayName("발급받지 않은 값은 통과하지 못한다")
    void forgedTokenIsRejected() {
        assertThat(store.consume("op_forged", "user_1", "doc_1")).isFalse();
        assertThat(store.consume(null, "user_1", "doc_1")).isFalse();
        assertThat(store.consume("", "user_1", "doc_1")).isFalse();
    }

    @Test
    @DisplayName("다른 사용자가 가로챈 표는 통과하지 못한다")
    void tokenIsBoundToIssuer() {
        String token = store.issue("user_1", "doc_1");

        assertThat(store.consume(token, "user_2", "doc_1")).isFalse();
    }

    @Test
    @DisplayName("다른 문서에 쓰려는 표는 통과하지 못한다")
    void tokenIsBoundToDocument() {
        String token = store.issue("user_1", "doc_1");

        assertThat(store.consume(token, "user_1", "doc_2")).isFalse();
    }

    @Test
    @DisplayName("표는 발급할 때마다 다르다")
    void tokensAreUnique() {
        assertThat(store.issue("user_1", "doc_1"))
                .isNotEqualTo(store.issue("user_1", "doc_1"));
    }
}
