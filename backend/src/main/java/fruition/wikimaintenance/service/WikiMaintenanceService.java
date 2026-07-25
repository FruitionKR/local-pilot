package fruition.wikimaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class WikiMaintenanceService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PipelineWikiMaintenanceRequester requester;

    public WikiMaintenanceService(WorkspaceMemberRepository workspaceMemberRepository,
                                  PipelineWikiMaintenanceRequester requester) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.requester = requester;
    }

    public JsonNode lint(String workspaceId, String userId, WikiLintRequest request) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return requester.lint(workspaceId, userId, request);
    }
}
