package fruition.core.aihistory.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 편집안에 붙는 일회용 적용 표. Agent turn에서 발급하고 저장 요청에서 소비한다.
 *
 * <p>{@code source=agent} 문자열은 클라이언트가 임의로 넣을 수 있어 수동 편집을 AI 작업으로
 * 위장할 수 있다. Backend가 발급한 값을 대조해야 로그가 오염되지 않는다.
 *
 * <p>표는 기존 {@code agent_runs}에 저장해 서버 재시작과 다중 인스턴스에서도 유지한다.
 */
@Component
public class AgentApplyOperationStore {

    private final JdbcTemplate jdbcTemplate;

    public AgentApplyOperationStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 편집안 하나에 대한 적용 표를 발급한다. */
    public String newOperationId() {
        return "op_" + randomSuffix();
    }

    /**
     * 표를 확인하고 소비한다. 같은 표로 두 번 기록되지 않도록 조회와 동시에 제거한다.
     *
     * @return 이 사용자·문서에 발급된 유효한 표면 {@code true}
     */
    public boolean consume(String operationId, String userId, String documentId) {
        if (operationId == null || operationId.isBlank()) {
            return false;
        }
        return jdbcTemplate.update("""
                UPDATE agent_runs
                SET apply_consumed_at = now(), updated_at = now()
                WHERE apply_operation_id = ?
                  AND user_id = ?
                  AND document_id = ?
                  AND action = 'markdown_turn'
                  AND status = 'completed'
                  AND apply_consumed_at IS NULL
                """, operationId, userId, documentId) == 1;
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
