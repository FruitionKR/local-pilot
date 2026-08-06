package fruition.core.document.controller;

import fruition.core.document.dto.BreadcrumbResponse;
import fruition.core.document.dto.FolderChildrenResponse;
import fruition.core.document.dto.HierarchySearchResponse;
import fruition.core.document.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 최상위 탐색: 워크스페이스 폴더 트리의 최상위 폴더·문서를 혼합 순서로 조회한다(TASK-H005). */
@RestController
@RequestMapping("/api/workspaces/{workspace_id}/navigation")
public class NavigationController {

    private final FolderService folderService;

    public NavigationController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping
    public ResponseEntity<FolderChildrenResponse> root(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(folderService.children(workspaceId, userId, null));
    }

    @GetMapping("/breadcrumb")
    public ResponseEntity<BreadcrumbResponse> breadcrumb(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam(value = "folder_id", required = false) UUID folderId,
            @RequestParam(value = "document_id", required = false) String documentId) {
        return ResponseEntity.ok(folderService.breadcrumb(workspaceId, userId, folderId, documentId));
    }

    @GetMapping("/search")
    public ResponseEntity<HierarchySearchResponse> search(
            @PathVariable("workspace_id") String workspaceId,
            @AuthenticationPrincipal String userId,
            @RequestParam("query") String query) {
        return ResponseEntity.ok(folderService.search(workspaceId, userId, query));
    }
}
