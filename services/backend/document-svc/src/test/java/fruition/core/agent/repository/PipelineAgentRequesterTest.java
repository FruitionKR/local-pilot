package fruition.core.agent.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.exception.PipelineAgentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineAgentRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedInternalToken = new AtomicReference<>();
    private final AtomicReference<String> capturedAgentToken = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(successResponse());
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/agent/turn", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedInternalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            capturedAgentToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void request_convertsEditorSnapshotToPipelineContract() {
        JsonNode response = requester().request("ws_1", "user_1", request());

        assertThat(response.path("action").asText()).isEqualTo("markdown_edit");
        assertThat(capturedBody.get())
                .contains("\"message\":\"문서를 점검해줘\"")
                .contains("\"active_markdown_context\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"start_line\":1")
                .contains("\"end_line\":2")
                .doesNotContain("documentId")
                .doesNotContain("baseVersion");
        assertThat(capturedAgentToken.get()).isEqualTo("test-agent-token");
        assertThat(capturedInternalToken.get()).isEqualTo("test-internal-callback");
    }

    @Test
    void request_preservesPipeline422Body() {
        responseStatus.set(422);
        responseBody.set("{\"detail\":{\"code\":\"markdown_output_contract_failed\",\"message\":\"교정 실패\"}}");

        assertThatThrownBy(() -> requester().request("ws_1", "user_1", request()))
                .isInstanceOfSatisfying(PipelineAgentException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(422);
                    assertThat(error.getResponseBody()).contains("markdown_output_contract_failed");
                });
    }

    private PipelineAgentRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/agent/turn";
        return new PipelineAgentRequester(
                new fruition.shared.http.PipelineClientFactory("test-internal-callback"),
                endpoint,
                5,
                "test-agent-token");
    }

    private AgentTurnRequest request() {
        return new AgentTurnRequest(
                "doc_1",
                3L,
                "문서를 점검해줘",
                null,
                new AgentTurnRequest.EditorSnapshot(
                        "# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2))
        );
    }

    private static String successResponse() {
        return "{\"action\":\"markdown_edit\","
                + "\"route\":{\"action\":\"markdown_edit\",\"confidence\":1,\"reason\":\"lint\",\"edit_goal\":\"cleanup\"},"
                + "\"message\":null,\"chat\":null,"
                + "\"edit\":{\"operation\":\"replace\",\"target\":{\"type\":\"whole_document\",\"start_line\":1,\"end_line\":2},"
                + "\"summary\":\"교정\",\"replacement_markdown\":\"# 제목\\n본문\"},\"generated_markdown\":null}";
    }
}
