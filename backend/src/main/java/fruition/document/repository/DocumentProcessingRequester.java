package fruition.document.repository;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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
        this.restClient = RestClient.create();
    }

    public void request(String documentId, String sourceUri) {
        try {
            ProcessResponse response = restClient.post()
                    .uri(processingEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ProcessRequest(documentId, sourceUri))
                    .retrieve()
                    .body(ProcessResponse.class);
            log.info("[처리 요청 완료] documentId={} status={}",
                    documentId, response != null ? response.status() : "null");
        } catch (RestClientResponseException e) {
            log.warn("[처리 요청 실패] documentId={} httpStatus={} body={}",
                    documentId, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[처리 요청 실패] documentId={} error={}", documentId, e.getMessage());
        }
    }

    private record ProcessRequest(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("source_uri") String sourceUri
    ) {}

    private record ProcessResponse(
            @JsonProperty("document_id") String documentId,
            String status
    ) {}
}
