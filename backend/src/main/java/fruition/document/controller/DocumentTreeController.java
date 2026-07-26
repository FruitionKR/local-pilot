package fruition.document.controller;

import fruition.document.dto.DocumentPlacementRequest;
import fruition.document.dto.DocumentPlacementResponse;
import fruition.document.dto.DocumentTreeResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.dto.FolderUpdateRequest;
import fruition.document.service.DocumentTreeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}")
public class DocumentTreeController {

    private final DocumentTreeService documentTreeService;

    public DocumentTreeController(DocumentTreeService documentTreeService) {
        this.documentTreeService = documentTreeService;
    }

    @GetMapping("/document-tree")
    public ResponseEntity<DocumentTreeResponse> tree(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(documentTreeService.getTree(workspaceId, userId));
    }

    @PostMapping("/folders")
    public ResponseEntity<FolderResponse> createFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody FolderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentTreeService.createFolder(workspaceId, userId, request));
    }

    @PatchMapping("/folders/{folder_id}")
    public ResponseEntity<FolderResponse> updateFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @Valid @RequestBody FolderUpdateRequest request) {
        return ResponseEntity.ok(documentTreeService.updateFolder(workspaceId, userId, folderId, request));
    }

    @DeleteMapping("/folders/{folder_id}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @RequestParam(value = "base_version", required = false) Long baseVersion) {
        documentTreeService.deleteFolder(workspaceId, userId, folderId, baseVersion);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/documents/{document_id}/placement")
    public ResponseEntity<DocumentPlacementResponse> placeDocument(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentPlacementRequest request) {
        return ResponseEntity.ok(documentTreeService.placeDocument(workspaceId, userId, documentId, request));
    }
}
