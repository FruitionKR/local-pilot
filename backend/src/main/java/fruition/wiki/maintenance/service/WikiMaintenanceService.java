package fruition.wiki.maintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wiki.maintenance.dto.WikiMaintenanceLintRequest;
import fruition.wiki.maintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class WikiMaintenanceService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PipelineWikiMaintenanceRequester pipelineWikiMaintenanceRequester;

    public WikiMaintenanceService(
            WorkspaceMemberRepository workspaceMemberRepository,
            PipelineWikiMaintenanceRequester pipelineWikiMaintenanceRequester) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.pipelineWikiMaintenanceRequester = pipelineWikiMaintenanceRequester;
    }

    public JsonNode lint(String workspaceId, String userId, WikiMaintenanceLintRequest request) {
        verifyWorkspaceMembership(workspaceId, userId);
        // 안전 기본값: dry_run은 proposal 조회, 두 값 모두 요청에서 생략되면 기본값을 쓴다.
        boolean dryRun = request == null || request.dryRun() == null || request.dryRun();
        boolean materializePromotions =
                request == null || request.materializePromotions() == null || request.materializePromotions();
        return pipelineWikiMaintenanceRequester.lint(workspaceId, userId, dryRun, materializePromotions);
    }

    private void verifyWorkspaceMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}
