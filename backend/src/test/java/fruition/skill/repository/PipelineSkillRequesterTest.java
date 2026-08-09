package fruition.skill.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.dto.SkillPublishRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineSkillRequesterTest {
    private HttpServer server;
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/skills", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] response = "{\"status\":\"proposal_ready\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void author_usesAuthorContractWithoutCapabilities() {
        requester().author("ws_1", "user_1", new SkillAuthoringRequest(
                "personal", "meeting-notes", null, "회의록을 작성해줘", "enhance", List.of("doc_1")));

        assertThat(uri.get()).isEqualTo("/skills/author");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(body.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"authoring_mode\":\"enhance\"")
                .contains("\"reference_document_ids\":[\"doc_1\"]")
                .doesNotContain("capabilities", "allowed_tools");
    }

    @Test
    void publish_usesAuthorPublishContract() {
        requester().publish("ws_1", "user_1", new SkillPublishRequest(
                "personal", "meeting-notes", "회의록 작성", "# 작성 절차", List.of("run_1")));

        assertThat(uri.get()).isEqualTo("/skills/author/publish");
        assertThat(body.get()).contains("\"instructions_markdown\":\"# 작성 절차\"");
        assertThat(body.get()).doesNotContain("source_run_ids");
    }

    private PipelineSkillRequester requester() {
        return new PipelineSkillRequester(
                "http://localhost:" + server.getAddress().getPort() + "/skills", "agent-token", 5);
    }
}
