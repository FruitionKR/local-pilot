package fruition.query.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.query.exception.PipelineQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
            @Value("${app.query.endpoint}") String queryEndpoint,
            @Value("${app.query.timeout-seconds:30}") int timeoutSeconds) {
        this.queryEndpoint = queryEndpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(timeoutSeconds * 1000);
        factory.setConnectTimeout(5000);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public PipelineQueryResponse query(String question) {
        try {
            return restClient.post()
                    .uri(queryEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new QueryPayload(question))
                    .retrieve()
                    .body(PipelineQueryResponse.class);
        } catch (ResourceAccessException e) {
            log.warn("[쿼리 파이프라인 타임아웃] question={} error={}", question, e.getMessage());
            throw new PipelineQueryException("PIPELINE_TIMEOUT", "쿼리 파이프라인 응답 시간이 초과되었습니다.", 503);
        } catch (RestClientResponseException e) {
            log.warn("[쿼리 파이프라인 오류] question={} httpStatus={} body={}",
                    question, e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode().value() >= 500) {
                throw new PipelineQueryException("PIPELINE_UNAVAILABLE", "쿼리 파이프라인을 사용할 수 없습니다.", 503);
            }
            throw new PipelineQueryException("PIPELINE_ERROR", "쿼리 파이프라인 요청이 거부되었습니다.", 502);
        }
    }

    private record QueryPayload(@JsonProperty("question") String question) {}
}
