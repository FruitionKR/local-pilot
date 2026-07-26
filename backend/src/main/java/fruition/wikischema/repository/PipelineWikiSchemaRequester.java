package fruition.wikischema.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import fruition.wikischema.exception.PipelineWikiSchemaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PipelineWikiSchemaRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineWikiSchemaRequester(
            @Value("${app.wiki-schema.endpoint}") String endpoint,
            @Value("${app.wiki-schema.timeout-seconds:30}") int timeoutSeconds) {
        this.endpoint = endpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode preview(String rawMarkdown) {
        return requireBody(post(endpoint + "/preview", new PreviewRequest(rawMarkdown)));
    }

    public JsonNode createDraft(String rawMarkdown, String workspaceId, String userId, String name) {
        return requireBody(post(endpoint + "/drafts", new DraftRequest(rawMarkdown, workspaceId, userId, name)));
    }

    public JsonNode activate(String schemaId) {
        try {
            return requireBody(restClient.post()
                    .uri(endpoint + "/{schemaId}/activate", schemaId)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (ResourceAccessException e) {
            throw timeout();
        } catch (RestClientResponseException e) {
            throw mapError(e);
        }
    }

    public JsonNode getActive(String workspaceId, String userId) {
        try {
            JsonNode response = restClient.get()
                    .uri(endpoint + "/active?workspace_id={workspaceId}&user_id={userId}", workspaceId, userId)
                    .retrieve()
                    .body(JsonNode.class);
            // 활성 스키마가 없으면 pipeline은 JSON null을 반환한다. 비어 있음을 그대로 노출한다.
            return response == null ? NullNode.getInstance() : response;
        } catch (ResourceAccessException e) {
            throw timeout();
        } catch (RestClientResponseException e) {
            throw mapError(e);
        }
    }

    private JsonNode post(String uri, Object body) {
        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw timeout();
        } catch (RestClientResponseException e) {
            throw mapError(e);
        }
    }

    private JsonNode requireBody(JsonNode response) {
        if (response == null) {
            throw new PipelineWikiSchemaException("Wiki 스키마 파이프라인 응답이 비어 있습니다.", 503, null);
        }
        return response;
    }

    private PipelineWikiSchemaException timeout() {
        return new PipelineWikiSchemaException("Wiki 스키마 파이프라인 응답 시간이 초과되었습니다.", 503, null);
    }

    private PipelineWikiSchemaException mapError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        // 400/422(요청 거부)와 404(스키마 없음)는 원본 status·body를 그대로 보존한다.
        if (status == 400 || status == 422 || status == 404) {
            throw new PipelineWikiSchemaException("Wiki 스키마 요청이 거부되었습니다.", status, e.getResponseBodyAsString());
        }
        throw new PipelineWikiSchemaException("Wiki 스키마 파이프라인을 사용할 수 없습니다.", 503, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PreviewRequest(@JsonProperty("raw_markdown") String rawMarkdown) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DraftRequest(
            @JsonProperty("raw_markdown") String rawMarkdown,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("name") String name
    ) {}
}
