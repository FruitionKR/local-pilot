package fruition.agent.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.exception.PipelineAgentException;
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
    private final AtomicReference<String> responseBody = new AtomicReference<>(successResponse());
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> internalToken = new AtomicReference<>();
    private final AtomicReference<String> agentToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/agent/turn", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            agentToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
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
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"skill_mode\":\"auto\"")
                .doesNotContain("\"skill_candidates\"")
                .doesNotContain("\"selected_skill\"")
                .contains("\"active_markdown_context\"")
                .contains("\"start_line\":1")
                .contains("\"end_line\":2")
                .doesNotContain("documentId")
                .doesNotContain("baseVersion");
        assertThat(internalToken.get()).isEqualTo("internal-token");
        assertThat(agentToken.get()).isEqualTo("agent-token");
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

    @Test
    void request_preservesSlashCommandForPipelineRouting() {
        AgentTurnRequest request = new AgentTurnRequest(
                "doc_1", 3L, "/meeting-summary 오늘 회의를 정리해줘", null,
                new AgentTurnRequest.EditorSnapshot(
                        "# 제목\n본문", new AgentTurnRequest.Target("whole_document", 1, 2)));

        requester().request("ws_1", "user_1", request);

        assertThat(capturedBody.get())
                .contains("\"message\":\"/meeting-summary 오늘 회의를 정리해줘\"")
                .contains("\"skill_mode\":\"auto\"")
                .doesNotContain("\"skill_id\"")
                .doesNotContain("\"selected_skill\"")
                .doesNotContain("\"skill_candidates\"");
    }

    private PipelineAgentRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/agent/turn";
        return new PipelineAgentRequester(endpoint, 5, "internal-token", "agent-token");
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
