package fruition.core.skill.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.core.authz.WorkspaceAiModelClient;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.dto.SkillPublishRequest;
import fruition.core.skill.dto.SkillUpdateRequest;
import fruition.shared.http.PipelineClientFactory;
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
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/skills", exchange -> {
            method.set(exchange.getRequestMethod());
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
    void author_usesServerControlledWorkspaceUserAndAgentToken() {
        requester().author("ws_1", "user_1", new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록을 작성해줘", "enhance", List.of("doc_1")),
                new WorkspaceAiModelClient.AiModelSelection("gemini", "gemini-2.5-flash-lite"));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/skills/author");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(body.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"reference_document_ids\":[\"doc_1\"]")
                .contains("\"provider\":\"gemini\"")
                .contains("\"model\":\"gemini-2.5-flash-lite\"")
                .doesNotContain("capabilities", "allowed_tools");
    }

    @Test
    void publish_usesWorkspaceModelSnapshot() {
        requester().publish("ws_1", "user_1",
                new SkillPublishRequest("team", "meeting-notes", "회의록 작성", "# 작성 절차"),
                new WorkspaceAiModelClient.AiModelSelection("claude", "claude-3-5-haiku-20241022"));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(uri.get()).isEqualTo("/skills/author/publish");
        assertThat(body.get())
                .contains("\"provider\":\"claude\"")
                .contains("\"model\":\"claude-3-5-haiku-20241022\"")
                .doesNotContain("api_key", "base_url");
    }

    @Test
    void list_usesWorkspaceAndUserQueryParameters() {
        requester().list("ws_1", "user_1");

        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).contains("workspace_id=ws_1").contains("user_id=user_1");
    }

    @Test
    void update_usesSkillPathAndScopePayload() {
        requester().update("ws_1", "user_1", "skill_1",
                new SkillUpdateRequest("meeting-notes", "회의록 작성", "# 작성 절차"),
                new WorkspaceAiModelClient.AiModelSelection("openai", "gpt-5-nano"));

        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(uri.get()).isEqualTo("/skills/skill_1");
        assertThat(body.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"instructions_markdown\":\"# 작성 절차\"")
                .contains("\"provider\":\"openai\"")
                .contains("\"model\":\"gpt-5-nano\"")
                .doesNotContain("api_key", "base_url");
    }

    private PipelineSkillRequester requester() {
        return new PipelineSkillRequester(
                new PipelineClientFactory("unused-internal-token"),
                "http://localhost:" + server.getAddress().getPort() + "/skills", "agent-token", 5);
    }
}
