package fruition.aihistory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.aihistory.repository.PipelineRestoreRequester;
import fruition.aihistory.service.WikiObjectReader;
import fruition.security.JwtTokenProvider;
import fruition.security.oauth.service.CustomOAuth2UserService;
import fruition.wikimaintenance.repository.PipelineWikiLintResponse;
import fruition.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** lint 실행부터 로그 조회와 되돌리기 요청까지 실제 Backend 경계를 연결한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LintOperationApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockBean PipelineWikiMaintenanceRequester maintenanceRequester;
    @MockBean PipelineRestoreRequester restoreRequester;
    @MockBean WikiObjectReader objectReader;
    @MockBean CustomOAuth2UserService customOAuth2UserService;

    private String userId;
    private String workspaceId;
    private String pageId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        pageId = "wp_" + suffix;

        jdbcTemplate.update("""
                INSERT INTO users(id, display_name, email, created_at, updated_at)
                VALUES (?, '통합 테스트', ?, now(), now())
                """, userId, userId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO workspaces(id, name, created_at, updated_at)
                VALUES (?, 'lint 통합 테스트', now(), now())
                """, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO workspace_members(joined_at, role, user_id, workspace_id)
                VALUES (now(), 'OWNER', ?, ?)
                """, userId, workspaceId);
        jdbcTemplate.update("""
                INSERT INTO wiki_pages(id, created_at, page_type, slug, status, title, updated_at,
                                       user_id, workspace_id)
                VALUES (?, now(), 'concept', ?, 'active', '기존 개념', now(), ?, ?)
                """, pageId, "concept-" + suffix, userId, workspaceId);

        String ingestOperationId = "op_ingest_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO ai_operation_logs(operation_id, workspace_id, user_id, operation_type,
                                              status, changed_resource_count, created_at, completed_at)
                VALUES (?, ?, ?, 'ingest', 'succeeded', 1, now(), now())
                """, ingestOperationId, workspaceId, userId);
        jdbcTemplate.update("""
                INSERT INTO wiki_page_versions(page_id, revision, contribution_count, markdown,
                                               markdown_key, content_hash, operation_id, created_by,
                                               created_at)
                VALUES (?, 1, 1, '# 기존 본문', ?, 'sha256:old', ?, ?, now())
                """, pageId, "wiki/old.md", ingestOperationId, userId);
        jdbcTemplate.update("""
                INSERT INTO wiki_page_contributions(page_id, ingest_operation_id, sequence_revision,
                                                    object_key, active, created_at)
                VALUES (?, ?, 1, ?, true, now())
                """, pageId, ingestOperationId,
                "wiki/" + workspaceId + "/pages/" + pageId + "/ops/" + ingestOperationId + ".json");

        when(maintenanceRequester.lint(eq(workspaceId), eq(userId), any(), anyString()))
                .thenAnswer(invocation -> lintResponse(invocation.getArgument(3)));
        when(objectReader.read(anyString(), eq(workspaceId), eq(pageId), anyString()))
                .thenReturn("# lint 이후 본문");
        when(objectReader.sha256("# lint 이후 본문")).thenReturn("sha256:lint");
        when(restoreRequester.sendLintRestore(any())).thenReturn(true);
    }

    @Test
    @DisplayName("lint 실행 결과를 저장하고 조회한 뒤 같은 작업을 되돌린다")
    void lintToLogAndRestore() throws Exception {
        String operationId = executeLint();

        mockMvc.perform(get("/api/workspaces/" + workspaceId + "/ai-operation-logs")
                        .header("Authorization", bearer())
                        .param("type", "lint")
                        .param("status", "succeeded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs[0].operation_id").value(operationId))
                .andExpect(jsonPath("$.logs[0].changed_resource_count").value(1));

        mockMvc.perform(get("/api/workspaces/" + workspaceId
                        + "/ai-operation-logs/" + operationId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].resource_id").value(pageId))
                .andExpect(jsonPath("$.changes[0].change_type").value("updated"))
                .andExpect(jsonPath("$.changes[0].before_revision").value(1))
                .andExpect(jsonPath("$.changes[0].after_revision").value(2))
                .andExpect(jsonPath("$.changes[0].hunks").isArray());

        String previewBody = mockMvc.perform(get("/api/workspaces/" + workspaceId
                        + "/ai-operation-logs/" + operationId + "/restore-preview")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuild_count").value(1))
                .andExpect(jsonPath("$.pages[0].page_id").value(pageId))
                .andExpect(jsonPath("$.pages[0].action").value("rebuild"))
                .andReturn().getResponse().getContentAsString();
        String previewToken = objectMapper.readTree(previewBody).path("preview_token").asText();

        mockMvc.perform(post("/api/workspaces/" + workspaceId
                        + "/ai-operation-logs/" + operationId + "/restore")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("preview_token", previewToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored_from").value(operationId))
                .andExpect(jsonPath("$.rebuild_count").value(1))
                .andExpect(jsonPath("$.status").value("rebuilding"));

        ArgumentCaptor<PipelineRestoreRequester.LintRestoreRun> captor =
                ArgumentCaptor.forClass(PipelineRestoreRequester.LintRestoreRun.class);
        verify(restoreRequester).sendLintRestore(captor.capture());
        assertThat(captor.getValue().targetOperationId()).isEqualTo(operationId);
        assertThat(captor.getValue().rebuildPages()).hasSize(1);
        assertThat(captor.getValue().rebuildPages().getFirst().pageId()).isEqualTo(pageId);
    }

    @Test
    @DisplayName("다른 워크스페이스 사용자에게 lint 로그를 노출하지 않는다")
    void rejectsUserOutsideWorkspace() throws Exception {
        String operationId = executeLint();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String outsiderId = "user_" + suffix;
        String outsiderWorkspaceId = "ws_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO users(id, display_name, email, created_at, updated_at)
                VALUES (?, '외부 사용자', ?, now(), now())
                """, outsiderId, outsiderId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO workspaces(id, name, created_at, updated_at)
                VALUES (?, '다른 워크스페이스', now(), now())
                """, outsiderWorkspaceId);
        jdbcTemplate.update("""
                INSERT INTO workspace_members(joined_at, role, user_id, workspace_id)
                VALUES (now(), 'OWNER', ?, ?)
                """, outsiderId, outsiderWorkspaceId);

        mockMvc.perform(get("/api/workspaces/" + workspaceId
                        + "/ai-operation-logs/" + operationId)
                        .header("Authorization", bearer(outsiderId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("lint 이후 같은 페이지가 변경됐으면 되돌리기 미리보기를 거절한다")
    void rejectsRestoreAfterLaterPageChange() throws Exception {
        String operationId = executeLint();
        String laterOperationId = "op_later_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO ai_operation_logs(operation_id, workspace_id, user_id, operation_type,
                                              status, changed_resource_count, created_at, completed_at)
                VALUES (?, ?, ?, 'lint', 'succeeded', 1, now(), now())
                """, laterOperationId, workspaceId, userId);
        jdbcTemplate.update("""
                INSERT INTO ai_operation_changes(operation_id, resource_type, resource_id,
                                                 before_revision, after_revision, change_type)
                VALUES (?, 'wiki_page', ?, 2, 3, 'updated')
                """, laterOperationId, pageId);

        mockMvc.perform(get("/api/workspaces/" + workspaceId
                        + "/ai-operation-logs/" + operationId + "/restore-preview")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RESTORE_REQUEST"));
    }

    @Test
    @DisplayName("dry_run은 결과만 반환하고 작업 로그와 페이지 버전을 만들지 않는다")
    void dryRunDoesNotCreateHistory() throws Exception {
        when(maintenanceRequester.lint(eq(workspaceId), eq(userId),
                argThat(request -> Boolean.TRUE.equals(request.dryRun())), isNull()))
                .thenReturn(PipelineWikiLintResponse.from(
                        objectMapper.readTree("{\"changed_pages\":[],\"orphan_refs\":[]}")));

        mockMvc.perform(post("/api/workspaces/" + workspaceId + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materializePromotions\":true,\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed_pages").isEmpty())
                .andExpect(jsonPath("$.orphan_refs").isArray());

        Long lintLogs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_logs WHERE workspace_id = ? AND operation_type = 'lint'",
                Long.class, workspaceId);
        Long versions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wiki_page_versions WHERE page_id = ?", Long.class, pageId);
        Long changes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_operation_changes WHERE resource_id = ?", Long.class, pageId);
        assertThat(lintLogs).isZero();
        assertThat(versions).isEqualTo(1L);
        assertThat(changes).isZero();
        verify(maintenanceRequester).lint(eq(workspaceId), eq(userId), any(), isNull());
    }

    private PipelineWikiLintResponse lintResponse(String operationId) throws Exception {
        String markdownKey = "wiki/" + workspaceId + "/pages/" + pageId
                + "/ops/" + operationId + ".md";
        JsonNode body = objectMapper.readTree("""
                {"operation_id":"%s","changed_pages":[
                  {"page_id":"%s","page_type":"concept","markdown_key":"%s",
                   "content_hash":"sha256:lint"}]}
                """.formatted(operationId, pageId, markdownKey));
        return PipelineWikiLintResponse.from(body);
    }

    private String executeLint() throws Exception {
        String lintBody = mockMvc.perform(post("/api/workspaces/" + workspaceId
                        + "/wiki/maintenance/lint")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materializePromotions\":true,\"dryRun\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(lintBody).path("operation_id").asText();
    }

    private String bearer() {
        return bearer(userId);
    }

    private String bearer(String targetUserId) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                targetUserId, targetUserId + "@example.com");
    }
}
