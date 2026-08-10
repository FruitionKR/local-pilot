package fruition.core.agent.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Component
public class PipelineAgentRunStatusRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineAgentRunStatusRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.agent.status-endpoint}") String endpoint,
            @Value("${app.agent.status-timeout-seconds:5}") int timeoutSeconds) {
        this.restClient = clientFactory.restClient(timeoutSeconds);
        this.endpoint = endpoint;
    }

    public Optional<RunStatus> find(String workspaceId, String userId, String runId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(endpoint + "/{runId}?workspace_id={workspaceId}&user_id={userId}",
                            runId, workspaceId, userId)
                    .retrieve()
                    .body(RunStatus.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (ResourceAccessException e) {
            throw new PipelineAgentException("Agent 상태 조회 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            throw new PipelineAgentException("Agent 상태를 조회하지 못했습니다.", 503,
                    e.getResponseBodyAsString());
        }
    }

    public record RunStatus(
            String id,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("base_version") long baseVersion,
            @JsonProperty("apply_operation_id") String applyOperationId,
            String status,
            JsonNode result,
            @JsonProperty("error_code") String errorCode
    ) {}
}
