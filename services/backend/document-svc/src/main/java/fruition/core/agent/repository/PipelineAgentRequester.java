package fruition.core.agent.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.exception.PipelineAgentException;
import fruition.shared.http.PipelineClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
public class PipelineAgentRequester {

    private final RestClient restClient;
    private final String endpoint;
    private final String agentServiceToken;

    public PipelineAgentRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.agent.endpoint}") String endpoint,
            @Value("${app.agent.timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.agent.service-token:}") String agentServiceToken) {
        this.endpoint = endpoint;
        this.agentServiceToken = agentServiceToken;
        this.restClient = clientFactory.restClient(timeoutSeconds);
    }

    public JsonNode request(String workspaceId, String userId, AgentTurnRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (!agentServiceToken.isBlank()) {
                            headers.set("X-Agent-Service-Token", agentServiceToken);
                        }
                    })
                    .body(toPayload(workspaceId, userId, request))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new PipelineAgentException("Agent 파이프라인 응답이 비어 있습니다.", 503, null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new PipelineAgentException("Agent 파이프라인 응답 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 422) {
                throw new PipelineAgentException("Agent 요청이 거부되었습니다.", status, e.getResponseBodyAsString());
            }
            throw new PipelineAgentException("Agent 파이프라인을 사용할 수 없습니다.", 503, null);
        }
    }

    private PipelineAgentRequest toPayload(
            String workspaceId,
            String userId,
            AgentTurnRequest request) {
        AgentTurnRequest.ConversationContext context = request.conversationContext();
        AgentTurnRequest.EditorSnapshot snapshot = request.editorSnapshot();
        AgentTurnRequest.Target target = snapshot.target();
        return new PipelineAgentRequest(
                workspaceId,
                userId,
                request.message(),
                context == null ? null : new PipelineConversationContext(
                        context.recentConversationSummary(), context.referenceContext()),
                new ActiveMarkdownContext(
                        snapshot.markdown(),
                        target == null ? null : new MarkdownTarget(
                                target.type(), target.startLine(), target.endLine()))
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PipelineAgentRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String message,
            @JsonProperty("conversation_context") PipelineConversationContext conversationContext,
            @JsonProperty("active_markdown_context") ActiveMarkdownContext activeMarkdownContext
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PipelineConversationContext(
            @JsonProperty("recent_conversation_summary") String recentConversationSummary,
            @JsonProperty("reference_context") Map<String, Object> referenceContext
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ActiveMarkdownContext(String markdown, MarkdownTarget target) {}

    private record MarkdownTarget(
            String type,
            @JsonProperty("start_line") int startLine,
            @JsonProperty("end_line") int endLine
    ) {}
}
