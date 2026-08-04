package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.DocumentRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.dto.RestorePreviewResponse;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.domain.WikiPageContribution;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복구 미리보기. 무엇이 삭제·복원·재작성되는지 계산해 보여주고 실행에 쓸 토큰을 발급한다.
 *
 * <p>본문을 읽지 않는다. 기여 명단만으로 끝나므로 저장소 접근이 없다.
 */
@Service
public class RestorePreviewService {

    private final OperationLogRepository operationLogRepository;
    private final WikiPageContributionRepository contributionRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final RestoreScopeResolver scopeResolver;
    private final RestorePlanner planner;
    private final PreviewTokenSigner tokenSigner;
    private final RestoreTargetValidator validator;
    private final DocumentRestorePlanner documentPlanner;

    public RestorePreviewService(OperationLogRepository operationLogRepository,
                                 WikiPageContributionRepository contributionRepository,
                                 WorkspaceMemberRepository workspaceMemberRepository,
                                 RestoreScopeResolver scopeResolver,
                                 RestorePlanner planner,
                                 PreviewTokenSigner tokenSigner,
                                 DocumentRestorePlanner documentPlanner,
                                 RestoreTargetValidator validator) {
        this.operationLogRepository = operationLogRepository;
        this.contributionRepository = contributionRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.tokenSigner = tokenSigner;
        this.documentPlanner = documentPlanner;
        this.validator = validator;
    }

    /**
     * 되돌리면 무엇이 바뀌는지 계산한다.
     *
     * <p>실행과 <b>같은 검증</b>을 거친다. 여기서 통과한 것이 실행에서 거절되면 사용자가 확인
     * 화면을 다 보고 나서 실패한다.
     */
    @Transactional(readOnly = true)
    public RestorePreviewResponse preview(String workspaceId, String userId, String operationId) {
        OperationLog target = loadOperation(workspaceId, userId, operationId);
        validator.requireRestorable(target);

        // 문서 편집은 되돌릴 버전이 변경내역에 이미 적혀 있어 계산할 것이 없다.
        if (target.getOperationType() == OperationType.document_edit) {
            DocumentRestorePlan plan = documentPlanner.plan(target);
            return RestorePreviewResponse.from(operationId, plan, tokenSigner.sign(operationId, plan));
        }

        Set<String> excluded = scopeResolver.resolve(target);
        Map<String, List<WikiPageContribution>> contributions = loadContributions(excluded);
        RestorePlan plan = planner.plan(excluded, contributions);
        validator.requireApplicable(target, plan);

        return RestorePreviewResponse.from(operationId, plan,
                tokenSigner.sign(operationId, contributions));
    }

    /**
     * 제외 대상이 건드린 페이지의 <b>전체</b> 기여를 페이지별로 모은다.
     * 판정과 토큰 서명이 같은 값을 봐야 하므로 한 번만 읽어 둘 다에 넘긴다.
     */
    Map<String, List<WikiPageContribution>> loadContributions(Set<String> excludedOperationIds) {
        if (excludedOperationIds.isEmpty()) {
            return Map.of();
        }
        List<String> pageIds =
                contributionRepository.findActivePageIdsByOperationIds(excludedOperationIds);
        if (pageIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<WikiPageContribution>> byPage = new LinkedHashMap<>();
        for (WikiPageContribution c : contributionRepository.findByPageIds(pageIds)) {
            byPage.computeIfAbsent(c.getPageId(), key -> new java.util.ArrayList<>()).add(c);
        }
        return byPage;
    }

    OperationLog loadOperation(String workspaceId, String userId, String operationId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return operationLogRepository.findByOperationIdAndWorkspaceId(operationId, workspaceId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
    }
}
