package fruition.wikimaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.aihistory.service.LintOperationStarter;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.service.OperationIngestService;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

@Service
public class WikiMaintenanceService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final PipelineWikiMaintenanceRequester requester;
    private final LintOperationStarter operationStarter;
    private final OperationIngestService operationIngestService;

    public WikiMaintenanceService(WorkspaceMemberRepository workspaceMemberRepository,
                                  PipelineWikiMaintenanceRequester requester,
                                  LintOperationStarter operationStarter,
                                  OperationIngestService operationIngestService) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.requester = requester;
        this.operationStarter = operationStarter;
        this.operationIngestService = operationIngestService;
    }

    public JsonNode lint(String workspaceId, String userId, WikiLintRequest request) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        if (request == null || !Boolean.FALSE.equals(request.dryRun())) {
            return requester.lint(workspaceId, userId, request, null).body();
        }

        String operationId = operationStarter.start(workspaceId, userId);
        try {
            var response = requester.lint(workspaceId, userId, request, operationId);
            operationIngestService.accept(operationId, new OperationResultRequest(
                    response.operationId(), "lint", "succeeded", workspaceId, userId, null,
                    "Wiki lint로 페이지 " + response.changedPages().size() + "개를 변경했습니다.",
                    response.changedPages().stream()
                            .map(page -> new OperationResultRequest.ChangedPage(
                                    page.pageId(), page.pageType(), page.markdownKey(),
                                    page.contributionKey(), page.contentHash(), false))
                            .toList(),
                    java.util.List.of()));
            return response.body();
        } catch (RuntimeException e) {
            operationStarter.markFailed(operationId, e.getMessage());
            throw e;
        }
    }
}
