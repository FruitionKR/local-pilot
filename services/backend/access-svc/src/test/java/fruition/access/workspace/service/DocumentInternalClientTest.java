package fruition.access.workspace.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.POST;

/** 초기 노트 생성 요청은 best-effort: 실패해도 예외가 전파되면 안 된다. */
class DocumentInternalClientTest {

    private MockRestServiceServer server;
    private DocumentInternalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://document.internal")
                .defaultHeader("X-Internal-Token", "test-internal-callback");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DocumentInternalClient(builder.build());
    }

    @Test
    @DisplayName("초기 노트 생성을 내부 토큰과 snake_case 본문으로 요청한다")
    void createInitialNote_sendsInternalRequest() {
        server.expect(requestTo("http://document.internal/internal/workspaces/ws_aaa11111/initial-note"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Token", "test-internal-callback"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"user_id\":\"user_1f9a74af\"}"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.createInitialNote("ws_aaa11111", "user_1f9a74af");

        server.verify();
    }

    @Test
    @DisplayName("document가 5xx로 실패해도 예외를 삼키고 워크스페이스 생성 흐름을 막지 않는다")
    void createInitialNote_serverError_doesNotThrow() {
        server.expect(requestTo("http://document.internal/internal/workspaces/ws_aaa11111/initial-note"))
                .andRespond(withServerError());

        assertThatCode(() -> client.createInitialNote("ws_aaa11111", "user_1f9a74af"))
                .doesNotThrowAnyException();

        server.verify();
    }
}
