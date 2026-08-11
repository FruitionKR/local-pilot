package fruition.core.wikischema.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.shared.util.ErrorResponse;
import fruition.core.wikischema.dto.WikiSchemaDraftRequest;
import fruition.core.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.core.wikischema.service.WikiSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/wiki-schema")
@Tag(name = "Wiki Schema", description = "Wiki 생성 규칙의 미리보기, 초안 저장 및 활성화 API")
public class WikiSchemaController {

    private final WikiSchemaService wikiSchemaService;

    public WikiSchemaController(WikiSchemaService wikiSchemaService) {
        this.wikiSchemaService = wikiSchemaService;
    }

    @Operation(summary = "Wiki Schema 미리보기", description = "Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "미리보기 생성 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaPreviewResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 또는 입력",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Schema 요청 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/preview")
    public ResponseEntity<JsonNode> preview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaPreviewRequest request) {
        return ResponseEntity.ok(wikiSchemaService.preview(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 초안 생성", description = "검토할 Wiki 생성 규칙을 초안 상태로 저장합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "초안 생성 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaDraftResponseSchema.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 정의",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "422", description = "Schema 요청 검증 실패",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/drafts")
    public ResponseEntity<JsonNode> createDraft(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaDraftRequest request) {
        return ResponseEntity.ok(wikiSchemaService.createDraft(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 활성화", description = "선택한 Wiki Schema ID의 활성화를 요청합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 성공",
            content = @Content(schema = @Schema(implementation = WikiSchemaResponseSchema.class))),
        @ApiResponse(responseCode = "404", description = "Schema 또는 워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{schema_id}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "활성화할 Wiki Schema ID", required = true)
            @PathVariable("schema_id") String schemaId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.activate(workspaceId, userId, schemaId));
    }

    @Operation(summary = "활성 Wiki Schema 조회", description = "활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성 Schema 조회 성공(null 가능)",
            content = @Content(schema = @Schema(implementation = WikiSchemaResponseSchema.class, nullable = true))),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(oneOf = {JsonNode.class, ErrorResponse.class}))),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActive(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.getActive(workspaceId, userId));
    }

    @Schema(name = "WikiSchemaPreviewResponse", requiredProperties = {"fragments", "issues", "preview_markdown", "has_blocked_issues"})
    private static final class WikiSchemaPreviewResponseSchema {
        public WikiSchemaFragmentsSchema fragments;
        public java.util.List<WikiSchemaIssueSchema> issues;
        public String preview_markdown;
        public boolean has_blocked_issues;
    }

    @Schema(name = "WikiSchemaDraftResponse", requiredProperties = "wiki_schema")
    private static final class WikiSchemaDraftResponseSchema {
        public WikiSchemaResponseSchema wiki_schema;
    }

    @Schema(name = "WikiSchemaResponse", requiredProperties = {"id", "workspace_id", "user_id", "name", "raw_markdown", "fragments", "issues", "preview_markdown", "has_blocked_issues", "status", "schema_version"})
    private static final class WikiSchemaResponseSchema {
        public String id;
        public String workspace_id;
        public String user_id;
        public String name;
        public String raw_markdown;
        public WikiSchemaFragmentsSchema fragments;
        public java.util.List<WikiSchemaIssueSchema> issues;
        public String preview_markdown;
        public boolean has_blocked_issues;
        public String status;
        public String schema_version;
        @Schema(nullable = true)
        public String created_at;
        @Schema(nullable = true)
        public String updated_at;
        @Schema(nullable = true)
        public String activated_at;
    }

    @Schema(name = "WikiSchemaFragmentsResponse", requiredProperties = {"global_markdown", "query_markdown", "ingest_markdown", "edit_markdown", "concept_markdown", "template_markdown"})
    private static final class WikiSchemaFragmentsSchema {
        public String global_markdown;
        public String query_markdown;
        public String ingest_markdown;
        public String edit_markdown;
        public String concept_markdown;
        public String template_markdown;
    }

    @Schema(name = "WikiSchemaIssueResponse", requiredProperties = {"severity", "category", "text", "reason"})
    private static final class WikiSchemaIssueSchema {
        @Schema(allowableValues = {"blocked", "unclear"})
        public String severity;
        public String category;
        public String text;
        public String reason;
        @Schema(nullable = true)
        public String section;
    }
}
