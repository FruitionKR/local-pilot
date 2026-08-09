package fruition.core.wikimaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.core.wiki.repository.WikiPageRepository;
import fruition.core.wikimaintenance.domain.WikiLintState;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.dto.WikiMaintenanceStatusResponse;
import fruition.core.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.core.wikimaintenance.repository.WikiLintStateRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class WikiMaintenanceService {

    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final PipelineWikiMaintenanceRequester requester;
    private final LintOperationStarter operationStarter;
    private final OperationIngestService operationIngestService;
    private final WikiLintStateRepository lintStateRepository;
    private final WikiPageRepository wikiPageRepository;

    public WikiMaintenanceService(WorkspaceAccessGuard workspaceAccessGuard,
                                  PipelineWikiMaintenanceRequester requester,
                                  LintOperationStarter operationStarter,
                                  OperationIngestService operationIngestService,
                                  WikiLintStateRepository lintStateRepository,
                                  WikiPageRepository wikiPageRepository) {
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.requester = requester;
        this.operationStarter = operationStarter;
        this.operationIngestService = operationIngestService;
        this.lintStateRepository = lintStateRepository;
        this.wikiPageRepository = wikiPageRepository;
    }

    public JsonNode lint(String workspaceId, String userId, WikiLintRequest request) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
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
            // lint가 바꾼 페이지의 updated_at보다 뒤 시각을 기록해, lint 자신의 변경이
            // 곧바로 needs_lint를 다시 켜는 루프를 막는다.
            lintStateRepository.upsert(workspaceId, Instant.now());
            return response.body();
        } catch (RuntimeException e) {
            operationStarter.markFailed(operationId, e.getMessage());
            throw e;
        }
    }

    /** 마지막 lint 성공 이후 위키 페이지가 변경됐으면 needs_lint = true. */
    public WikiMaintenanceStatusResponse status(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Instant lastLintAt = lintStateRepository.findById(workspaceId)
                .map(WikiLintState::getLastLintAt)
                .orElse(null);
        Instant lastWikiChangeAt = wikiPageRepository.findMaxUpdatedAtByWorkspaceId(workspaceId)
                .orElse(null);
        boolean needsLint = lastWikiChangeAt != null
                && (lastLintAt == null || lastWikiChangeAt.isAfter(lastLintAt));
        return new WikiMaintenanceStatusResponse(needsLint, lastLintAt, lastWikiChangeAt);
    }
}
