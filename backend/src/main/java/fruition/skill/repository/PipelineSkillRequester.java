package fruition.skill.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.dto.SkillPublishRequest;
import fruition.skill.dto.SkillUpdateRequest;
import fruition.skill.exception.PipelineSkillException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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

    public JsonNode author(String workspaceId, String userId, SkillAuthoringRequest request) {
        return post("/author", new AuthorPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instruction(), request.authoringMode(), request.referenceDocumentIds()));
    }

    public JsonNode publish(String workspaceId, String userId, SkillPublishRequest request) {
        return post("/author/publish", new PublishPayload(workspaceId, userId, request.scopeType(), request.name(),
                request.description(), request.instructionsMarkdown()));
    }

    public JsonNode list(String workspaceId, String userId) {
        return get("?workspace_id=" + workspaceId + "&user_id=" + userId);
    }

    public JsonNode get(String workspaceId, String userId, String skillId) {
        return get("/" + skillId + "?workspace_id=" + workspaceId + "&user_id=" + userId);
    }

    public JsonNode update(String workspaceId, String userId, String skillId, SkillUpdateRequest request) {
        return patch("/" + skillId, new UpdatePayload(workspaceId, userId, request.name(),
                request.description(), request.instructionsMarkdown()));
    }

    public JsonNode setEnabled(String workspaceId, String userId, String skillId, boolean enabled) {
        return post("/" + skillId + (enabled ? "/enable" : "/disable"), new ActorPayload(workspaceId, userId));
    }

    private JsonNode get(String path) {
        try {
            return requireBody(restClient.get().uri(endpoint + path).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return requireBody(restClient.post().uri(endpoint + path).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode patch(String path, Object body) {
        try {
            return requireBody(restClient.patch().uri(endpoint + path).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class));
        } catch (ResourceAccessException exception) {
            throw unavailable("Skill 파이프라인 응답 시간이 초과되었습니다.");
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        }
    }

    private JsonNode requireBody(JsonNode response) {
        if (response == null || response.isNull()) {
            throw unavailable("Skill 파이프라인 응답이 비어 있습니다.");
        }
        return response;
    }

    private PipelineSkillException translate(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 400 || status == 404 || status == 409 || status == 410 || status == 413 || status == 422) {
            return new PipelineSkillException("Skill 요청이 거부되었습니다.", status,
                    exception.getResponseBodyAsString());
        }
        return unavailable("Skill 파이프라인을 사용할 수 없습니다.");
    }

    private PipelineSkillException unavailable(String message) {
        return new PipelineSkillException(message, 503, null);
    }

    private record AuthorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            String instruction,
            @JsonProperty("authoring_mode") String authoringMode,
            @JsonProperty("reference_document_ids") java.util.List<String> referenceDocumentIds) {}

    private record PublishPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("scope_type") String scopeType,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown) {}

    private record UpdatePayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            String name,
            String description,
            @JsonProperty("instructions_markdown") String instructionsMarkdown) {}

    private record ActorPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId) {}
}
