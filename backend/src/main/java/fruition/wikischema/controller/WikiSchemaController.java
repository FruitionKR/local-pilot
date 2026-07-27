package fruition.wikischema.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikischema.dto.CreateWikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.service.WikiSchemaService;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Wiki Schema", description = "Wiki 분류 스키마 미리보기·초안·활성화 API")
public class WikiSchemaController {

    private final WikiSchemaService wikiSchemaService;

    public WikiSchemaController(WikiSchemaService wikiSchemaService) {
        this.wikiSchemaService = wikiSchemaService;
    }

    @Operation(summary = "Wiki 스키마 미리보기", description = "원본 Markdown으로 분류 스키마를 미리 생성해 봅니다.")
    @PostMapping("/preview")
    public ResponseEntity<JsonNode> preview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaPreviewRequest request) {
        return ResponseEntity.ok(wikiSchemaService.preview(workspaceId, userId, request.rawMarkdown()));
    }

    @Operation(summary = "Wiki 스키마 초안 저장", description = "분류 스키마 초안을 저장합니다.")
    @PostMapping("/drafts")
    public ResponseEntity<JsonNode> createDraft(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateWikiSchemaDraftRequest request) {
        return ResponseEntity.ok(wikiSchemaService.createDraft(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki 스키마 활성화", description = "선택한 스키마 초안을 활성 스키마로 전환합니다.")
    @PostMapping("/{schema_id}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("schema_id") String schemaId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.activate(workspaceId, userId, schemaId));
    }

    @Operation(summary = "활성 Wiki 스키마 조회", description = "현재 적용 중인 활성 스키마를 반환합니다.")
    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActive(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.getActive(workspaceId, userId));
    }
}
