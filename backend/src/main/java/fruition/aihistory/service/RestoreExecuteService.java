package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.DocumentRestorePlan;
import fruition.aihistory.dto.RestoreExecuteResponse;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.exception.RestorePreviewStaleException;
import fruition.aihistory.repository.PipelineRestoreRequester;
import fruition.wiki.domain.WikiPageContribution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 복구 실행. 미리보기에서 본 상태가 아직 그대로인지 확인하고 반영한 뒤, 재작성만 llmPipeline에 맡긴다.
 *
 * <p>ingest 되돌리기는 <b>Wiki만</b> 다룬다. ingest는 원문 문서를 읽기만 하고 바꾸지 않으므로
 * 되돌릴 문서 본문이 없다.
 */
@Service
public class RestoreExecuteService {

    private final RestorePreviewService previewService;
    private final RestoreScopeResolver scopeResolver;
    private final RestorePlanner planner;
    private final PreviewTokenSigner tokenSigner;
    private final RestoreOperationLifecycle lifecycle;
    private final DocumentRestorePlanner documentPlanner;
    private final DocumentRestoreApplier documentApplier;
    private final RestoreApplier applier;
    private final PipelineRestoreRequester restoreRequester;
    private final ObjectMapper objectMapper;
    private final String callbackBaseUrl;

    public RestoreExecuteService(RestorePreviewService previewService,
                                 RestoreScopeResolver scopeResolver,
                                 RestorePlanner planner,
                                 PreviewTokenSigner tokenSigner,
                                 RestoreOperationLifecycle lifecycle,
                                 DocumentRestorePlanner documentPlanner,
                                 DocumentRestoreApplier documentApplier,
                                 RestoreApplier applier,
                                 PipelineRestoreRequester restoreRequester,
                                 ObjectMapper objectMapper,
                                 @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.previewService = previewService;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.tokenSigner = tokenSigner;
        this.lifecycle = lifecycle;
        this.documentPlanner = documentPlanner;
        this.documentApplier = documentApplier;
        this.applier = applier;
        this.restoreRequester = restoreRequester;
        this.objectMapper = objectMapper;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public RestoreExecuteResponse execute(String workspaceId, String userId,
                                          String operationId, String previewToken) {
        OperationLog target = previewService.loadOperation(workspaceId, userId, operationId);
        if (target.getOperationType() == OperationType.document_edit) {
            return executeDocument(target, previewToken);
        }
        if (target.getOperationType() != OperationType.ingest
                && target.getOperationType() != OperationType.lint) {
            throw new InvalidRestoreRequestException(
                    "되돌릴 수 없는 작업입니다: " + target.getOperationType());
        }

        Set<String> excluded = scopeResolver.resolve(target);
        Map<String, List<WikiPageContribution>> contributions =
                previewService.loadContributions(excluded);

        // 미리보기 이후 대상이 바뀌었으면 실행하지 않는다. 되돌리기는 무를 수 없다.
        if (!tokenSigner.matches(previewToken, operationId, contributions)) {
            throw new RestorePreviewStaleException();
        }

        RestorePlan plan = planner.plan(excluded, contributions);
        if (plan.pages().isEmpty()) {
            throw new InvalidRestoreRequestException("되돌릴 Wiki 페이지가 없습니다.");
        }

        Instant now = Instant.now();
        OperationLog restore = lifecycle.start(target, manifestJson(plan), now);

        List<PipelineRestoreRequester.RestoreRun.RestoredPage> restored =
                applier.apply(restore, plan, excluded, now);

        boolean notified = notify(restore, target, excluded, plan, restored);
        lifecycle.finish(restore.getOperationId(), plan, notified, now);

        return RestoreExecuteResponse.from(restore.getOperationId(), operationId, plan, notified);
    }

    /**
     * 문서 편집 되돌리기. Wiki와 달리 재작성이 없어 llmPipeline을 부르지 않고 그 자리에서 끝난다.
     */
    private RestoreExecuteResponse executeDocument(OperationLog target, String previewToken) {
        DocumentRestorePlan plan = documentPlanner.plan(target);
        if (!tokenSigner.matches(previewToken, target.getOperationId(), plan)) {
            throw new RestorePreviewStaleException();
        }

        Instant now = Instant.now();
        OperationLog restore = lifecycle.start(target, manifestJson(plan), now);
        long newVersion = documentApplier.apply(restore, plan);
        lifecycle.finishDocument(restore.getOperationId(), plan.toVersion(), newVersion, now);

        return RestoreExecuteResponse.forDocument(
                restore.getOperationId(), target.getOperationId());
    }

    private boolean notify(OperationLog restore, OperationLog target, Set<String> excluded,
                           RestorePlan plan,
                           List<PipelineRestoreRequester.RestoreRun.RestoredPage> restored) {
        return restoreRequester.send(PipelineRestoreRequester.RestoreRun.from(
                restore.getOperationId(), target.getOperationId(), restore.getWorkspaceId(),
                restore.getUserId(),
                callbackBaseUrl + "/api/ai-operations/" + restore.getOperationId() + "/result",
                excluded, plan, restored));
    }

    /** 지시서 원본을 보관한다. 재조립 결과를 받을 때 목표 기여 수를 여기서 꺼내면 그사이 새 ingest가 들어와도 값이 안 흔들린다. */
    private String manifestJson(Object plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("복구 지시서를 직렬화하지 못했습니다.", e);
        }
    }
}
