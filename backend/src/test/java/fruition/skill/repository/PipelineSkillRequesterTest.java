package fruition.skill.repository;

import com.sun.net.httpserver.HttpServer;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.service.SkillReferenceDocument;
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
    private final AtomicReference<String> capturedUri = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/skills", exchange -> {
            capturedUri.set(exchange.getRequestURI().toString());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Service-Token"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
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

    @Test
    void review_usesExistingPreviewContractAndAgentToken() {
        requester().review("ws_1", "user_1", draft(), references());

        assertThat(capturedUri.get()).isEqualTo("/skills/preview");
        assertThat(capturedToken.get()).isEqualTo("agent-token");
        assertThat(capturedBody.get())
                .contains("\"workspace_id\":\"ws_1\"")
                .contains("\"user_id\":\"user_1\"")
                .contains("\"instructions_markdown\":\"문서를 요약한다.\"")
                .contains("\"scope_type\":\"personal\"")
                .contains("\"reference_documents\"")
                .contains("\"content_hash\":\"hash-1\"");
    }

    @Test
    void refine_usesPlannedRefinePath() {
        requester().refine("ws_1", "user_1", draft(), references());

        assertThat(capturedUri.get()).isEqualTo("/skills/refine");
    }

    @Test
    void publish_usesPlannedAtomicPublishPathAndReviewToken() {
        requester().publish("ws_1", "user_1", draft(), references(), "review-token");

        assertThat(capturedUri.get()).isEqualTo("/skills/publish-reviewed");
        assertThat(capturedBody.get()).contains("\"review_token\":\"review-token\"");
    }

    private PipelineSkillRequester requester() {
        String endpoint = "http://localhost:" + server.getAddress().getPort() + "/skills";
        return new PipelineSkillRequester(endpoint, "agent-token", 5);
    }

    private SkillDraftRequest draft() {
        return new SkillDraftRequest(
                "summary", "요약", "문서를 요약한다.", "personal", List.of("doc_1"),
                "문서 요약 Skill", List.of("document-create"), List.of("create_document"));
    }

    private List<SkillReferenceDocument> references() {
        return List.of(new SkillReferenceDocument("doc_1", "문서", "hash-1", "본문"));
    }
}
