package fruition.wikischema.controller;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikischema.dto.CreateWikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.service.WikiSchemaService;
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
public class WikiSchemaController {

    private final WikiSchemaService wikiSchemaService;

    public WikiSchemaController(WikiSchemaService wikiSchemaService) {
        this.wikiSchemaService = wikiSchemaService;
    }

    @PostMapping("/preview")
    public ResponseEntity<JsonNode> preview(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody WikiSchemaPreviewRequest request) {
        return ResponseEntity.ok(wikiSchemaService.preview(workspaceId, userId, request.rawMarkdown()));
    }

    @PostMapping("/drafts")
    public ResponseEntity<JsonNode> createDraft(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateWikiSchemaDraftRequest request) {
        return ResponseEntity.ok(wikiSchemaService.createDraft(workspaceId, userId, request));
    }

    @PostMapping("/{schema_id}/activate")
    public ResponseEntity<JsonNode> activate(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("schema_id") String schemaId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.activate(workspaceId, userId, schemaId));
    }

    @GetMapping("/active")
    public ResponseEntity<JsonNode> getActive(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(wikiSchemaService.getActive(workspaceId, userId));
    }
}
