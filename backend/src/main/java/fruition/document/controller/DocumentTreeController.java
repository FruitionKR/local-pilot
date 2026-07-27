package fruition.document.controller;

import fruition.document.dto.DocumentPlacementRequest;
import fruition.document.dto.DocumentPlacementResponse;
import fruition.document.dto.DocumentTreeResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.dto.FolderUpdateRequest;
import fruition.document.service.DocumentTreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Document Tree", description = "폴더·문서 트리 조회 및 폴더 관리·배치 API")
public class DocumentTreeController {

    private final DocumentTreeService documentTreeService;

    public DocumentTreeController(DocumentTreeService documentTreeService) {
        this.documentTreeService = documentTreeService;
    }

    @Operation(summary = "폴더·문서 트리 조회", description = "워크스페이스의 폴더와 문서를 트리 구조로 반환합니다.")
    @GetMapping("/document-tree")
    public ResponseEntity<DocumentTreeResponse> tree(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(documentTreeService.getTree(workspaceId, userId));
    }

    @Operation(summary = "폴더 생성", description = "새 폴더를 만듭니다.")
    @PostMapping("/folders")
    public ResponseEntity<FolderResponse> createFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody FolderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentTreeService.createFolder(workspaceId, userId, request));
    }

    @Operation(summary = "폴더 이름 변경/이동", description = "폴더 이름을 바꾸거나 다른 폴더로 이동합니다. 순환 배치는 차단됩니다.")
    @PatchMapping("/folders/{folder_id}")
    public ResponseEntity<FolderResponse> updateFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @Valid @RequestBody FolderUpdateRequest request) {
        return ResponseEntity.ok(documentTreeService.updateFolder(workspaceId, userId, folderId, request));
    }

    @Operation(summary = "폴더 삭제(cascade)", description = "폴더와 하위 폴더·문서를 함께 소프트 삭제합니다.")
    @DeleteMapping("/folders/{folder_id}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @RequestParam(value = "base_version", required = false) Long baseVersion) {
        documentTreeService.deleteFolder(workspaceId, userId, folderId, baseVersion);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "폴더 복구(cascade)", description = "삭제한 폴더와 하위 항목을 함께 복구합니다.")
    @PostMapping("/folders/{folder_id}/restore")
    public ResponseEntity<Void> restoreFolder(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("folder_id") UUID folderId,
            @RequestParam(value = "base_version", required = false) Long baseVersion) {
        documentTreeService.restoreFolder(workspaceId, userId, folderId, baseVersion);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "문서 폴더 배치/이동", description = "문서를 특정 폴더로 배치하거나 이동합니다.")
    @PatchMapping("/documents/{document_id}/placement")
    public ResponseEntity<DocumentPlacementResponse> placeDocument(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @PathVariable("document_id") String documentId,
            @Valid @RequestBody DocumentPlacementRequest request) {
        return ResponseEntity.ok(documentTreeService.placeDocument(workspaceId, userId, documentId, request));
    }
}
