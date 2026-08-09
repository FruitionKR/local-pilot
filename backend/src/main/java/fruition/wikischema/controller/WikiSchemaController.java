package fruition.wikischema.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikischema.dto.WikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.service.WikiSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

    @Operation(summary = "Wiki Schema 미리보기", description = "Schema 규칙을 저장하지 않고 llmPipeline에 적용해 예상 Wiki 구조를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "미리보기 생성 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 또는 입력"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음"),
        @ApiResponse(responseCode = "503", description = "llmPipeline 사용 불가")
    })
    @PostMapping("/preview")
    public ResponseEntity<JsonNode> preview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaPreviewRequest request) {
        return ResponseEntity.ok(wikiSchemaService.preview(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 초안 생성", description = "검토할 Wiki 생성 규칙을 초안 상태로 저장합니다. 기존 활성 Schema는 유지됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "초안 생성 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 Schema 정의"),
        @ApiResponse(responseCode = "404", description = "워크스페이스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "Schema 이름 또는 버전 충돌")
    })
    @PostMapping("/drafts")
    public ResponseEntity<JsonNode> createDraft(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaDraftRequest request) {
        return ResponseEntity.ok(wikiSchemaService.createDraft(workspaceId, userId, request));
    }

    @Operation(summary = "Wiki Schema 활성화", description = "선택한 초안을 워크스페이스의 Wiki 생성에 사용할 활성 Schema로 전환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성화 성공"),
        @ApiResponse(responseCode = "404", description = "Schema 또는 워크스페이스를 찾을 수 없음"),
        @ApiResponse(responseCode = "409", description = "Schema 상태 충돌")
    })
    @PostMapping("/{schema_id}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "활성화할 Wiki Schema ID")
            @PathVariable("schema_id") String schemaId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.activate(workspaceId, userId, schemaId));
    }

    @Operation(summary = "활성 Wiki Schema 조회", description = "현재 워크스페이스의 Wiki 생성에 적용되는 활성 Schema를 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "활성 Schema 조회 성공"),
        @ApiResponse(responseCode = "404", description = "활성 Schema 또는 워크스페이스를 찾을 수 없음")
    })
    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActive(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.getActive(workspaceId, userId));
    }
}
