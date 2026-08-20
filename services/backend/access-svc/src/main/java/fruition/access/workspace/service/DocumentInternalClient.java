package fruition.access.workspace.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * document(문서 서비스) 내부 API client.
 * 워크스페이스 생성 직후 초기 노트 작성을 요청한다({@code POST /internal/workspaces/{id}/initial-note}).
 *
 * <p>초기 노트는 편의 기능이라 best-effort로 처리한다:
 * 호출 실패 시 warn 로그만 남기고 워크스페이스 생성은 성공시킨다.
 */
@Component
public class DocumentInternalClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentInternalClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    @Autowired
    public DocumentInternalClient(@Value("${app.internal.document-base-url}") String documentBaseUrl,
                                  @Value("${app.internal.callback-token}") String internalToken) {
        this(buildRestClient(documentBaseUrl, internalToken));
    }

    DocumentInternalClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildRestClient(String documentBaseUrl, String internalToken) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(documentBaseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /** 초기 노트 생성 요청. 실패해도 예외를 전파하지 않는다(best-effort). */
    public void createInitialNote(String workspaceId, String userId) {
        try {
            restClient.post()
                    .uri("/internal/workspaces/{workspaceId}/initial-note", workspaceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("user_id", userId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("초기 노트 생성 요청 실패로 건너뜁니다. workspaceId={}", workspaceId, e);
        }
    }
}
