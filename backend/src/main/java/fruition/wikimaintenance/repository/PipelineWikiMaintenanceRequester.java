package fruition.wikimaintenance.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.exception.PipelineWikiMaintenanceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PipelineWikiMaintenanceRequester {

    private final RestClient restClient;
    private final String endpoint;

    public PipelineWikiMaintenanceRequester(
            @Value("${app.wiki-maintenance.endpoint}") String endpoint,
            @Value("${app.wiki-maintenance.timeout-seconds:200}") int timeoutSeconds) {
        this.endpoint = endpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode lint(String workspaceId, String userId, WikiLintRequest request) {
        WikiLintRequest safe = request == null ? new WikiLintRequest(null, null) : request;
        try {
            JsonNode response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LintPayload(userId, workspaceId, safe.materializePromotions(), safe.dryRun()))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw new PipelineWikiMaintenanceException("Wiki maintenance lint 응답이 비어 있습니다.", 503, null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new PipelineWikiMaintenanceException("Wiki maintenance lint 응답 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 422) {
                throw new PipelineWikiMaintenanceException("Wiki maintenance lint 요청이 거부되었습니다.", status,
                        e.getResponseBodyAsString());
            }
            throw new PipelineWikiMaintenanceException("Wiki maintenance lint 파이프라인을 사용할 수 없습니다.", 503, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record LintPayload(
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("materialize_promotions") Boolean materializePromotions,
            @JsonProperty("dry_run") Boolean dryRun
    ) {}
}
