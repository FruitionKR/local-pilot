package fruition.wikischema.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import fruition.wikischema.exception.PipelineWikiSchemaException;
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

class PipelineWikiSchemaRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"has_blocked_issues\":false}");
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/wiki-schema", exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedQuery.set(exchange.getRequestURI().getQuery());
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
    void preview_sendsRawMarkdownAndReturnsBody() {
        JsonNode response = requester().preview("# 제목");

        assertThat(capturedPath.get()).isEqualTo("/wiki-schema/preview");
        assertThat(capturedBody.get()).contains("\"raw_markdown\":\"# 제목\"");
        assertThat(response.path("has_blocked_issues").asBoolean()).isFalse();
    }

    @Test
    void createDraft_sendsWorkspaceUserAndName() {
        requester().createDraft("# 제목", "ws_1", "user_1", "규칙집");

        assertThat(capturedPath.get()).isEqualTo("/wiki-schema/drafts");
        assertThat(capturedBody.get())
                .contains("\"raw_markdown\":\"# 제목\"")
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"name\":\"규칙집\"");
    }

    @Test
    void createDraft_omitsNullName() {
        requester().createDraft("# 제목", "ws_1", "user_1", null);

        assertThat(capturedBody.get()).doesNotContain("\"name\"");
    }

    @Test
    void activate_callsSchemaActivatePath() {
        requester().activate("schema_9");

        assertThat(capturedPath.get()).isEqualTo("/wiki-schema/schema_9/activate");
    }

    @Test
    void getActive_passesWorkspaceAndUserAsQuery() {
        requester().getActive("ws_1", "user_1");

        assertThat(capturedPath.get()).isEqualTo("/wiki-schema/active");
        assertThat(capturedQuery.get()).isEqualTo("workspace_id=ws_1&user_id=user_1");
    }

    @Test
    void getActive_returnsNullNodeWhenNoActiveSchema() {
        responseBody.set("null");

        JsonNode response = requester().getActive("ws_1", "user_1");

        assertThat(response.isNull()).isTrue();
    }

    @Test
    void preview_preservesPipeline400Body() {
        responseStatus.set(400);
        responseBody.set("{\"detail\":\"raw_markdown가 비어 있습니다\"}");

        assertThatThrownBy(() -> requester().preview("# 제목"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(400);
                    assertThat(error.getResponseBody()).contains("raw_markdown");
                });
    }

    @Test
    void activate_preservesPipeline404Body() {
        responseStatus.set(404);
        responseBody.set("{\"detail\":\"schema not found\"}");

        assertThatThrownBy(() -> requester().activate("missing"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(404);
                    assertThat(error.getResponseBody()).contains("schema not found");
                });
    }

    @Test
    void preview_mapsPipeline500To503() {
        responseStatus.set(500);
        responseBody.set("{\"detail\":\"boom\"}");

        assertThatThrownBy(() -> requester().preview("# 제목"))
                .isInstanceOfSatisfying(PipelineWikiSchemaException.class, error -> {
                    assertThat(error.getHttpStatus()).isEqualTo(503);
                    assertThat(error.getResponseBody()).isNull();
                });
    }

    private PipelineWikiSchemaRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/wiki-schema";
        return new PipelineWikiSchemaRequester(endpoint, 5);
    }
}
