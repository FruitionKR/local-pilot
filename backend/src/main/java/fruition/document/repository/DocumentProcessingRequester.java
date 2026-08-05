package fruition.document.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    private final String chatEndpoint;

    public DocumentProcessingRequester(
            @Value("${app.processing.endpoint}") String processingEndpoint,
            @Value("${app.processing.chat-endpoint}") String chatEndpoint) {
        this.processingEndpoint = processingEndpoint;
        this.chatEndpoint = chatEndpoint;
        this.restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    /** chatWiki=true면 채팅 Wiki page화 전용 엔드포인트(/chat-wiki/runs)로, 아니면 일반 문서 처리 엔드포인트로 보낸다. */
    public PipelineRunResponse request(String documentId, String userId, String workspaceId, String callbackUrl,
                                       String selectionMode, String inputMarkdown, boolean chatWiki) {
        return request(documentId, userId, workspaceId, callbackUrl, selectionMode, inputMarkdown, chatWiki,
                null, null);
    }

    /**
     * @param operationId       AI 작업 로그 식별자. llmPipeline이 결과 콜백에 그대로 실어 보낸다
     * @param resultCallbackUrl 완료 결과를 보낼 곳. 진행 로그용 {@code callbackUrl}과 별개다
     */
    public PipelineRunResponse request(String documentId, String userId, String workspaceId, String callbackUrl,
                                       String selectionMode, String inputMarkdown, boolean chatWiki,
                                       String operationId, String resultCallbackUrl) {
        String endpoint = chatWiki ? chatEndpoint : processingEndpoint;
        PipelineRunRequest body = new PipelineRunRequest(documentId, userId, workspaceId, callbackUrl,
                selectionMode, inputMarkdown, operationId, resultCallbackUrl);
        log.info("[파이프라인 요청 데이터] endpoint={} documentId={} userId={} workspaceId={} chatWiki={} selectionMode={} callbackUrl={} inputMarkdownPresent={} inputMarkdownLength={}",
                endpoint,
                documentId,
                userId,
                workspaceId,
                chatWiki,
                selectionMode,
                callbackUrl,
                inputMarkdown != null,
                inputMarkdown != null ? inputMarkdown.length() : 0);
        try {
            PipelineRunResponse response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PipelineRunResponse.class);
            log.info("[파이프라인 실행 요청 완료] endpoint={} documentId={} workspaceId={} runId={} status={}",
                    endpoint,
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
            @JsonProperty("log_callback_url") String logCallbackUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("selection_mode") String selectionMode,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("input_markdown") String inputMarkdown,
            // llmPipeline의 PipelineRunIn이 extra="forbid"라 스키마가 준비되기 전에는 보내면 422가 난다.
            // null이면 직렬화에서 빠지므로 기존 동작에 영향이 없다.
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("operation_id") String operationId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("result_callback_url") String resultCallbackUrl
    ) {}

    public record PipelineRunResponse(
            @JsonProperty("run_id") String runId,
            String status,
            @JsonProperty("output_dir") String outputDir,
            @JsonProperty("log_path") String logPath
    ) {}
}
