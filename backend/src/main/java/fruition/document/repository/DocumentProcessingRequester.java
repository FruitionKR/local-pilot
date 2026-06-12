package fruition.document.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DocumentProcessingRequester {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingRequester.class);

    private final RestClient restClient;
    private final String processingEndpoint;

    public DocumentProcessingRequester(
            @Value("${app.processing.endpoint}") String processingEndpoint) {
        this.processingEndpoint = processingEndpoint;
        this.restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    public void request(String documentId) {
        try {
            PipelineRunResponse response = restClient.post()
                    .uri(processingEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"document_id\":\"" + documentId + "\"}")
                    .retrieve()
                    .body(PipelineRunResponse.class);
            log.info("[파이프라인 실행 요청 완료] documentId={} runId={} status={}",
                    documentId,
                    response != null ? response.runId() : "null",
                    response != null ? response.status() : "null");
        } catch (RestClientResponseException e) {
            log.warn("[파이프라인 실행 요청 실패] documentId={} httpStatus={} body={}",
                    documentId, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[파이프라인 실행 요청 실패] documentId={} error={}", documentId, e.getMessage());
        }
    }

    private record PipelineRunResponse(
            @JsonProperty("run_id") String runId,
            String status,
            @JsonProperty("output_dir") String outputDir,
            @JsonProperty("log_path") String logPath
    ) {}
}
