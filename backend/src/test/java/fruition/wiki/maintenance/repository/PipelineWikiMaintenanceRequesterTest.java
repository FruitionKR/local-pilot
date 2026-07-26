package fruition.wiki.maintenance.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.wiki.maintenance.exception.PipelineWikiMaintenanceException;
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

class PipelineWikiMaintenanceRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"cluster_count\":3}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki/maintenance/lint", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    void lint_sendsScopeAndFlagsWithoutLlmSecrets() {
        JsonNode response = requester().lint("ws_1", "user_1", true, true);

        assertThat(capturedPath.get()).isEqualTo("/wiki/maintenance/lint");
        assertThat(capturedBody.get())
                .contains("\"user_id\":\"user_1\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"dry_run\":true")
                .contains("\"materialize_promotions\":true")
                .doesNotContain("api_key")
                .doesNotContain("provider");
        assertThat(response.path("cluster_count").asInt()).isEqualTo(3);
    }

    @Test
    void lint_sendsExecuteFlagsWhenDryRunFalse() {
        requester().lint("ws_1", "user_1", false, true);

        assertThat(capturedBody.get()).contains("\"dry_run\":false");
    }

    @Test
    void lint_preservesPipeline400Body() {
        responseStatus.set(400);
        responseBody.set("{\"detail\":\"active cluster를 찾을 수 없습니다\"}");

        assertThatThrownBy(() -> requester().lint("ws_1", "user_1", true, true))
                .isInstanceOfSatisfying(PipelineWikiMaintenanceException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(400);
                    assertThat(error.getResponseBody()).contains("active cluster");
                });
    }

    @Test
    void lint_mapsPipeline500To503() {
        responseStatus.set(500);
        responseBody.set("{\"detail\":{\"code\":\"internal_server_error\"}}");

        assertThatThrownBy(() -> requester().lint("ws_1", "user_1", true, true))
                .isInstanceOfSatisfying(PipelineWikiMaintenanceException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(503);
                    assertThat(error.getResponseBody()).isNull();
                });
    }

    private PipelineWikiMaintenanceRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki/maintenance/lint";
        return new PipelineWikiMaintenanceRequester(endpoint, 5);
    }
}
