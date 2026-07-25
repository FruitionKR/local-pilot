package fruition.document.controller;

import fruition.document.dto.FolderChildrenResponse;
import fruition.document.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
