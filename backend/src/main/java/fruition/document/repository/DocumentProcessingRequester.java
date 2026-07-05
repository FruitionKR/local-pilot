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

    public PipelineRunResponse request(String documentId, String userId, String workspaceId, String callbackUrl) {
        PipelineRunRequest body = new PipelineRunRequest(documentId, userId, workspaceId, callbackUrl);
        try {
            PipelineRunResponse response = restClient.post()
                    .uri(processingEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PipelineRunResponse.class);
            log.info("[파이프라인 실행 요청 완료] documentId={} workspaceId={} runId={} status={}",
                    documentId,
                    workspaceId,
                    response != null ? response.runId() : "null",
                    response != null ? response.status() : "null");
            return response;
        } catch (RestClientResponseException e) {
            String msg = "[파이프라인 실행 요청 실패] documentId=" + documentId
                    + " httpStatus=" + e.getStatusCode()
                    + " body=" + e.getResponseBodyAsString();
            log.warn(msg);
            throw new RuntimeException(msg, e);
        } catch (Exception e) {
            String msg = "[파이프라인 실행 요청 실패] documentId=" + documentId + " error=" + e.getMessage();
            log.warn(msg);
            throw new RuntimeException(msg, e);
        }
    }

    public record PipelineRunRequest(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("log_callback_url") String logCallbackUrl
    ) {}

    public record PipelineRunResponse(
            @JsonProperty("run_id") String runId,
            String status,
            @JsonProperty("output_dir") String outputDir,
            @JsonProperty("log_path") String logPath
    ) {}
}
