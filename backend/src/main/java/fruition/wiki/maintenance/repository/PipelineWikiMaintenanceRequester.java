package fruition.wiki.maintenance.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import fruition.wiki.maintenance.exception.PipelineWikiMaintenanceException;
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
    private final String lintEndpoint;

    public PipelineWikiMaintenanceRequester(
            @Value("${app.wiki-maintenance.lint-endpoint}") String lintEndpoint,
            @Value("${app.wiki-maintenance.timeout-seconds:180}") int timeoutSeconds) {
        this.lintEndpoint = lintEndpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public JsonNode lint(String workspaceId, String userId, boolean dryRun, boolean materializePromotions) {
        try {
            JsonNode response = restClient.post()
                    .uri(lintEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LintRequest(userId, workspaceId, dryRun, materializePromotions))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new PipelineWikiMaintenanceException("Wiki maintenance 파이프라인 응답이 비어 있습니다.", 503, null);
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new PipelineWikiMaintenanceException("Wiki maintenance 파이프라인 응답 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 400/422(요청 거부)는 원본 status·body를 보존하고, 그 외는 503으로 매핑한다.
            if (status == 400 || status == 422) {
                throw new PipelineWikiMaintenanceException(
                        "Wiki maintenance 요청이 거부되었습니다.", status, e.getResponseBodyAsString());
            }
            throw new PipelineWikiMaintenanceException("Wiki maintenance 파이프라인을 사용할 수 없습니다.", 503, null);
        }
    }

    // LLM provider·비밀 설정은 보내지 않는다. pipeline이 자체 env 기본값을 사용한다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record LintRequest(
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("dry_run") boolean dryRun,
            @JsonProperty("materialize_promotions") boolean materializePromotions
    ) {}
}
