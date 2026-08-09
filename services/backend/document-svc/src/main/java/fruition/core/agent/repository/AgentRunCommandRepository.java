package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** 기존 agent_runs/agent_jobs에 Markdown Agent Kafka 실행을 기록한다. */
@Repository
public class AgentRunCommandRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRunCommandRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void create(String runId, String workspaceId, String userId, String documentId,
                       long baseVersion, String applyOperationId, String instruction) {
        jdbcTemplate.update("""
                INSERT INTO agent_runs (
                    id, workspace_id, user_id, action, status, request_summary,
                    document_id, base_version, apply_operation_id
                ) VALUES (?, ?, ?, 'markdown_turn', 'queued', ?, ?, ?, ?)
                """, runId, workspaceId, userId, instruction.substring(0, Math.min(1000, instruction.length())),
                documentId, baseVersion, applyOperationId);
        jdbcTemplate.update("""
                INSERT INTO agent_jobs (id, run_id, job_type, status)
                VALUES (?, ?, 'markdown_turn', 'queued')
                """, UUID.randomUUID().toString(), runId);
    }

    public Optional<RunView> find(String workspaceId, String userId, String runId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, base_version, apply_operation_id, status, result, error_code
                FROM agent_runs
                WHERE id = ? AND workspace_id = ? AND user_id = ? AND action = 'markdown_turn'
                """, rs -> rs.next() ? Optional.of(new RunView(
                        rs.getString("id"), rs.getString("document_id"), rs.getLong("base_version"),
                        rs.getString("apply_operation_id"), rs.getString("status"),
                        json(rs.getString("result")), rs.getString("error_code"))) : Optional.empty(),
                runId, workspaceId, userId);
    }

    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Agent result를 읽지 못했습니다.", e);
        }
    }

    public record RunView(String runId, String documentId, long baseVersion,
                          String applyOperationId, String status, JsonNode result, String error) {}
}
