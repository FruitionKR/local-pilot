package fruition.core.agent.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.shared.http.PipelineClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineAgentRunStatusRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/agent/runs", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            token.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            if (exchange.getRequestURI().getQuery().contains("workspace_id=foreign")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] response = """
                    {"id":"run-1","workspace_id":"ws-1","action":"workspace_workflow",
                     "status":"completed","request_summary":"정식 요청","error_code":null,
                     "plan":{"id":"plan-1","version":1,"summary":"정식 계획","operation_hash":"hash",
                     "status":"approved","operations":[{"id":"operation-1","sequence":1,
                     "tool_name":"move_document","target_type":"document","target_id":"doc-1",
                     "base_version":1,"source_parent_id":null,"destination_parent_id":"folder-1",
                     "arguments":{},"reason":"정식 이유","depends_on":[],"status":"succeeded",
                     "error_code":null}]}}
                    """.getBytes(StandardCharsets.UTF_8);
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
    void findAutonomousReadsScopedCanonicalAgentRunWithAgentToken() {
        var run = requester().findAutonomous("ws-1", "user-1", "run-1").orElseThrow();

        assertThat(uri.get()).isEqualTo("/agent/runs/run-1?workspace_id=ws-1&user_id=user-1");
        assertThat(token.get()).isEqualTo("agent-token");
        assertThat(run.requestSummary()).isEqualTo("정식 요청");
        assertThat(run.plan().summary()).isEqualTo("정식 계획");
        assertThat(run.plan().operations().getFirst().toolName()).isEqualTo("move_document");
        assertThat(run.plan().operations().getFirst().reason()).isEqualTo("정식 이유");
    }

    @Test
    void findAutonomousReturnsEmptyForMissingOrForeignRun() {
        assertThat(requester().findAutonomous("foreign", "user-1", "run-1")).isEmpty();
    }

    private PipelineAgentRunStatusRequester requester() {
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        return new PipelineAgentRunStatusRequester(
                new PipelineClientFactory("internal-token"), baseUrl + "/internal/agent/runs",
                baseUrl + "/agent/runs", "agent-token", 5);
    }
}
