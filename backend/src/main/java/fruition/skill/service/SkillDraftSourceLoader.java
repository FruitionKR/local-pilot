package fruition.skill.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.skill.exception.InvalidSkillRequestException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SkillDraftSourceLoader {
    private final JdbcClient jdbcClient;

    public SkillDraftSourceLoader(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public LoadedSources load(String workspaceId, String userId, List<String> runIds) {
        if (new LinkedHashSet<>(runIds).size() != runIds.size()) {
            throw new InvalidSkillRequestException("AgentRun은 중복해서 선택할 수 없습니다.");
        }
        List<RunRow> rows = jdbcClient.sql("""
                        SELECT run.id, run.status, run.request_summary, plan.summary AS plan_summary
                        FROM agent_runs run
                        JOIN agent_plans plan ON plan.id = run.current_plan_id
                        WHERE run.id IN (:runIds) AND run.workspace_id = :workspaceId AND run.user_id = :userId
                        """)
                .param("runIds", runIds).param("workspaceId", workspaceId).param("userId", userId)
                .query(RunRow.class).list();
        Map<String, RunRow> byId = rows.stream().collect(Collectors.toMap(RunRow::id, Function.identity()));
        if (byId.size() != runIds.size()) {
            throw new InvalidSkillRequestException("접근 가능한 AgentRun을 찾을 수 없습니다.");
        }

        List<SourceRun> sources = new ArrayList<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>(runIds);
        for (String runId : runIds) {
            RunRow run = byId.get(runId);
            if (!"completed".equals(run.status())) {
                throw new InvalidSkillRequestException("완료된 AgentRun만 Skill 제안에 사용할 수 있습니다.");
            }
            List<OperationRow> operations = jdbcClient.sql("""
                            SELECT operation.tool_name, operation.reason, operation.target_id,
                                   operation.source_parent_id, operation.destination_parent_id
                            FROM agent_plan_operations operation
                            JOIN agent_runs run ON run.current_plan_id = operation.plan_id
                            WHERE run.id = :runId AND operation.status = 'succeeded'
                            ORDER BY operation.sequence
                            """)
                    .param("runId", runId).query(OperationRow.class).list();
            if (operations.isEmpty()) {
                throw new InvalidSkillRequestException("성공한 operation이 있는 AgentRun만 사용할 수 있습니다.");
            }
            operations.forEach(operation -> {
                addIfPresent(excluded, operation.targetId());
                addIfPresent(excluded, operation.sourceParentId());
                addIfPresent(excluded, operation.destinationParentId());
            });
            sources.add(new SourceRun(run.id(), run.status(), run.requestSummary(), run.planSummary(),
                    operations.stream().map(operation -> new SourceOperation(operation.toolName(), operation.reason()))
                            .toList()));
        }
        return new LoadedSources(sources, List.copyOf(excluded));
    }

    private static void addIfPresent(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private record RunRow(String id, String status, String requestSummary, String planSummary) {}
    private record OperationRow(String toolName, String reason, String targetId,
                                String sourceParentId, String destinationParentId) {}
    public record LoadedSources(List<SourceRun> sourceRuns, List<String> excludedLiterals) {}
    public record SourceRun(@JsonProperty("run_id") String runId, String status,
                            @JsonProperty("request_summary") String requestSummary,
                            @JsonProperty("plan_summary") String planSummary,
                            @JsonProperty("successful_operations") List<SourceOperation> successfulOperations) {}
    public record SourceOperation(@JsonProperty("tool_name") String toolName, String reason) {}
}
