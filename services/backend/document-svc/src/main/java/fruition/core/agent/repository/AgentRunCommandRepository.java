package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** core가 소유하는 Markdown Agent 적용 예약 projection을 기록한다. */
@Repository
public class AgentRunCommandRepository {

    private final JdbcTemplate jdbcTemplate;
    public AgentRunCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String runId, String workspaceId, String userId, String documentId,
                       long baseVersion, String applyOperationId) {
        jdbcTemplate.update("""
                INSERT INTO agent_apply_projections (
                    run_id, workspace_id, user_id, document_id, base_version,
                    apply_operation_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'queued')
                """, runId, workspaceId, userId, documentId, baseVersion, applyOperationId);
    }

    public Optional<RunView> find(String workspaceId, String userId, String runId) {
        return jdbcTemplate.query("""
                SELECT run_id, document_id, base_version, apply_operation_id, status, result, error_code
                FROM agent_apply_projections
                WHERE run_id = ? AND workspace_id = ? AND user_id = ?
                """, rs -> rs.next() ? Optional.of(new RunView(
                        rs.getString("run_id"), rs.getString("document_id"), rs.getLong("base_version"),
                        rs.getString("apply_operation_id"), rs.getString("status"),
                        null, rs.getString("error_code"))) : Optional.empty(),
                runId, workspaceId, userId);
    }

    public record RunView(String runId, String documentId, long baseVersion,
                          String applyOperationId, String status, JsonNode result, String error) {}
}
