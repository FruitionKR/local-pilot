package fruition.core.query.repository;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineQueryRequesterTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>(minimalPipelineResponseJson());

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/query", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private PipelineQueryRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/query";
        return new PipelineQueryRequester(
                new fruition.shared.http.PipelineClientFactory("test-internal-callback"), endpoint, 5);
    }

    @Test
    void query_withoutRequestId_omitsRunFieldsFromRequestBody() {
        requester().query("ws_abc123", "질문");

        assertThat(capturedBody.get())
                .contains("\"workspace_id\":\"ws_abc123\"")
                .contains("\"question\":\"질문\"")
                .contains("\"provider\":\"openai\"")
                .contains("\"model\":\"gpt-4.1-mini\"")
                .contains("\"allow_web_search\":false")
                .doesNotContain("request_id")
                .doesNotContain("log_callback_url");
    }

    @Test
    void query_sendsAllowWebSearchAsBoolean() {
        requester().query("ws_abc123", "질문", "openai", "gpt-4.1-mini", true);

        assertThat(capturedBody.get()).contains("\"allow_web_search\":true");
    }

    private static String minimalPipelineResponseJson() {
        return "{\"answer\":\"답변\",\"related_pages\":[],\"evidence_snippets\":[],"
                + "\"graph_context\":{\"nodes\":[],\"edges\":[]},\"traversal_paths\":[]}";
    }
}
