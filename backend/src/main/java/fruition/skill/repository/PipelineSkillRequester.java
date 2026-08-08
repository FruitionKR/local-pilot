package fruition.skill.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.exception.PipelineSkillException;
import fruition.skill.service.SkillReferenceDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Component
public class PipelineSkillRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineSkillRequester(
            @Value("${app.skill.endpoint}") String endpoint,
            @Value("${app.skill.agent-token}") String agentToken,
            @Value("${app.skill.timeout-seconds:60}") int timeoutSeconds
    ) {
        this.endpoint = endpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Agent-Service-Token", agentToken)
                .build();
    }

    public JsonNode refine(
            String workspaceId, String userId, SkillDraftRequest draft,
            List<SkillReferenceDocument> references) {
        return post("/refine", payload(workspaceId, userId, draft, references, null));
    }

    public JsonNode review(
            String workspaceId, String userId, SkillDraftRequest draft,
            List<SkillReferenceDocument> references) {
        return post("/preview", payload(workspaceId, userId, draft, references, null));
    }

    public JsonNode publish(
            String workspaceId, String userId, SkillDraftRequest draft,
            List<SkillReferenceDocument> references, String reviewToken) {
        return post("/publish-reviewed", payload(workspaceId, userId, draft, references, reviewToken));
    }

    private SkillPayload payload(
            String workspaceId, String userId, SkillDraftRequest draft,
            List<SkillReferenceDocument> references, String reviewToken) {
        return new SkillPayload(
                workspaceId, userId, draft.command(), draft.name(), draft.description(), draft.instructions(),
                draft.scope(), draft.capabilities(), draft.allowedTools(), references, reviewToken);
    }

    private JsonNode post(String path, Object body) {
        try {
            JsonNode response = restClient.post()
                    .uri(endpoint + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw unavailable("Skill 파이프라인 응답이 비어 있습니다.");
            }
            return response;
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 409 || status == 410 || status == 413 || status == 422) {
                throw new PipelineSkillException(
                        "Skill 요청이 거부되었습니다.", status, exception.getResponseBodyAsString());
            }
            throw unavailable("Skill 파이프라인을 사용할 수 없습니다.");
        }
    }

    private PipelineSkillException unavailable(String message) {
        return new PipelineSkillException(message, 503, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SkillPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String command,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructions,
            @JsonProperty("scope_type") String scope,
            List<String> capabilities,
            @JsonProperty("allowed_tools") List<String> allowedTools,
            @JsonProperty("reference_documents") List<SkillReferenceDocument> referenceDocuments,
            @JsonProperty("review_token") String reviewToken
    ) {}
}
