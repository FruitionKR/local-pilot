package fruition.core.document.controller;

import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentPositionResponse;
import fruition.core.document.service.DocumentPlacementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/documents")
public class DocumentPositionController {

    private final DocumentPlacementService documentPlacementService;

    public DocumentPositionController(DocumentPlacementService documentPlacementService) {
        this.documentPlacementService = documentPlacementService;
    }

    @PatchMapping("/{document_id}/position")
    public ResponseEntity<DocumentPositionResponse> move(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DocumentPositionRequest request) {
        return ResponseEntity.ok(
                documentPlacementService.move(workspaceId, userId, documentId, idempotencyKey, request));
    }
}
