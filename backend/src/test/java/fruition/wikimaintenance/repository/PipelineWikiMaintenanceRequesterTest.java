package fruition.wikimaintenance.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.exception.PipelineWikiMaintenanceException;
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
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(successResponse());
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki/maintenance/lint", exchange -> {
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
    void lint_injectsUserAndWorkspaceSnakeCase() {
        JsonNode response = requester().lint("ws_1", "user_1", new WikiLintRequest(true, false));

        assertThat(response.path("workspace_id").asText()).isEqualTo("ws_1");
        assertThat(capturedBody.get())
                .contains("\"user_id\":\"user_1\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"materialize_promotions\":true")
                .contains("\"dry_run\":false");
    }

    @Test
    void lint_omitsNullOptionsSoPipelineDefaultsApply() {
        requester().lint("ws_1", "user_1", new WikiLintRequest(null, null));

        assertThat(capturedBody.get())
                .contains("\"user_id\":\"user_1\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .doesNotContain("materialize_promotions")
                .doesNotContain("dry_run");
    }

    @Test
    void lint_treatsNullRequestAsDefaults() {
        requester().lint("ws_1", "user_1", null);

        assertThat(capturedBody.get())
                .contains("\"user_id\":\"user_1\"")
                .doesNotContain("materialize_promotions")
                .doesNotContain("dry_run");
    }

    @Test
    void lint_preservesPipeline400Body() {
        responseStatus.set(400);
        responseBody.set("{\"detail\":\"missing upstage api key\"}");

        assertThatThrownBy(() -> requester().lint("ws_1", "user_1", new WikiLintRequest(null, null)))
                .isInstanceOfSatisfying(PipelineWikiMaintenanceException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(400);
                    assertThat(error.getResponseBody()).contains("missing upstage api key");
                });
    }

    @Test
    void lint_mapsServerErrorTo503() {
        responseStatus.set(500);
        responseBody.set("{\"detail\":{\"code\":\"internal_server_error\"}}");

        assertThatThrownBy(() -> requester().lint("ws_1", "user_1", new WikiLintRequest(null, null)))
                .isInstanceOfSatisfying(PipelineWikiMaintenanceException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(503);
                    assertThat(error.getResponseBody()).isNull();
                });
    }

    private PipelineWikiMaintenanceRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki/maintenance/lint";
        return new PipelineWikiMaintenanceRequester(endpoint, 5);
    }

    private static String successResponse() {
        return "{\"user_id\":\"user_1\",\"workspace_id\":\"ws_1\","
                + "\"active_path\":\"wiki/user_1/ws_1/clusters/active.md\",\"cluster_count\":2,"
                + "\"source_ref_count\":5,\"orphan_refs\":[],\"promotion_candidates\":[],\"needs_review\":[],"
                + "\"relation_candidates\":[],\"invalid_relations\":[],\"invalid_promotions\":[],"
                + "\"materialized_promotions\":[],\"merged_promotions\":[],\"materialized_relations\":[]}";
    }
}
