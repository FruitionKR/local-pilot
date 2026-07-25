package fruition.document.controller;

import fruition.document.dto.FolderChildrenResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.service.FolderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<FolderResponse> create(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(folderService.create(workspaceId, userId, idempotencyKey, request));
    }

    @PatchMapping("/{folder_id}")
    public ResponseEntity<FolderResponse> rename(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderRenameRequest request) {
        return ResponseEntity.ok(folderService.rename(workspaceId, userId, folderId, idempotencyKey, request));
    }

    @PatchMapping("/{folder_id}/position")
    public ResponseEntity<FolderResponse> move(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FolderPositionRequest request) {
        return ResponseEntity.ok(folderService.move(workspaceId, userId, folderId, idempotencyKey, request));
    }

    @GetMapping("/{folder_id}/children")
    public ResponseEntity<FolderChildrenResponse> children(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId) {
        return ResponseEntity.ok(folderService.children(workspaceId, userId, folderId));
    }
}
