package fruition.core.query.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.exception.PipelineQueryException;
import fruition.shared.http.PipelineClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PipelineQueryRequester {

    private static final Logger log = LoggerFactory.getLogger(PipelineQueryRequester.class);

    private final RestClient restClient;
    private final String queryEndpoint;

    public PipelineQueryRequester(
            PipelineClientFactory clientFactory,
            @Value("${app.query.endpoint}") String queryEndpoint,
            @Value("${app.query.timeout-seconds:30}") int timeoutSeconds) {
        this.queryEndpoint = queryEndpoint;
        this.restClient = clientFactory.restClient(timeoutSeconds);
    }

    public PipelineQueryResponse query(String workspaceId, String question) {
        return query(workspaceId, question, "openai", "gpt-5-nano");
    }

    public PipelineQueryResponse query(String workspaceId, String question, String provider, String model) {
        return query(workspaceId, question, provider, model, false);
    }

    public PipelineQueryResponse query(String workspaceId, String question, String provider, String model,
                                       boolean webSearchEnabled) {
        return executeQuery(new QueryPayload(workspaceId, question, provider, model, webSearchEnabled));
    }

    private PipelineQueryResponse executeQuery(QueryPayload payload) {
        try {
            log.info("[쿼리 파이프라인 요청 데이터] endpoint={} workspaceId={} questionLength={}",
                    queryEndpoint,
                    payload.workspaceId(),
                    payload.question().length());
            PipelineQueryResponse response = restClient.post()
                    .uri(queryEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(PipelineQueryResponse.class);
            log.info("[쿼리 파이프라인 응답 완료] answerLength={} relatedPageCount={} evidenceCount={}",
                    response != null && response.answer() != null ? response.answer().length() : 0,
                    response != null && response.relatedPages() != null ? response.relatedPages().size() : 0,
                    response != null && response.evidenceSnippets() != null ? response.evidenceSnippets().size() : 0);
            return response;
        } catch (ResourceAccessException e) {
            log.warn("[쿼리 파이프라인 타임아웃] questionLength={} error={}",
                    payload.question().length(), e.getMessage());
            throw new PipelineQueryException("PIPELINE_TIMEOUT", "쿼리 파이프라인 응답 시간이 초과되었습니다.", 503, null);
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.warn("[쿼리 파이프라인 오류] questionLength={} httpStatus={} body={}",
                    payload.question().length(), e.getStatusCode(), body);
            if (e.getStatusCode().value() >= 500) {
                throw new PipelineQueryException("PIPELINE_UNAVAILABLE", "쿼리 파이프라인을 사용할 수 없습니다.", 503, body);
            }
            throw new PipelineQueryException("PIPELINE_ERROR", "쿼리 파이프라인 요청이 거부되었습니다.", 502, body);
        }
    }

    private record QueryPayload(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("question") String question,
            String provider,
            String model,
            @JsonProperty("allow_web_search") boolean webSearchEnabled) {}
}
