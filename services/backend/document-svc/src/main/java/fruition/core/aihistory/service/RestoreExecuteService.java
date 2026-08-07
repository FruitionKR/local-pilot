package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestoreExecuteResponse;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.repository.PipelineRestoreRequester;
import fruition.core.wiki.domain.WikiPageContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RestoreExecuteService.class);

    private final RestorePreviewService previewService;
    private final RestoreScopeResolver scopeResolver;
    private final RestorePlanner planner;
    private final LintRestorePlanner lintRestorePlanner;
    private final PreviewTokenSigner tokenSigner;
    private final RestoreOperationLifecycle lifecycle;
    private final DocumentRestorePlanner documentPlanner;
    private final DocumentRestoreApplier documentApplier;
    private final RestoreApplier applier;
    private final PipelineRestoreRequester restoreRequester;
    private final RestoreTargetValidator validator;
    private final ObjectMapper objectMapper;
    private final String callbackBaseUrl;

    public RestoreExecuteService(RestorePreviewService previewService,
                                 RestoreScopeResolver scopeResolver,
                                 RestorePlanner planner,
                                 LintRestorePlanner lintRestorePlanner,
                                 PreviewTokenSigner tokenSigner,
                                 RestoreOperationLifecycle lifecycle,
                                 DocumentRestorePlanner documentPlanner,
                                 DocumentRestoreApplier documentApplier,
                                 RestoreApplier applier,
                                 PipelineRestoreRequester restoreRequester,
                                 RestoreTargetValidator validator,
                                 ObjectMapper objectMapper,
                                 @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.previewService = previewService;
        this.scopeResolver = scopeResolver;
        this.planner = planner;
        this.lintRestorePlanner = lintRestorePlanner;
        this.tokenSigner = tokenSigner;
        this.lifecycle = lifecycle;
        this.documentPlanner = documentPlanner;
        this.documentApplier = documentApplier;
        this.applier = applier;
        this.restoreRequester = restoreRequester;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public RestoreExecuteResponse execute(String workspaceId, String userId,
                                          String operationId, String previewToken) {
        OperationLog target = previewService.loadOperation(workspaceId, userId, operationId);
        validator.requireRestorable(target);
        if (target.getOperationType() == OperationType.document_edit) {
            return executeDocument(target, previewToken);
        }

        Set<String> excluded = scopeResolver.resolve(target);
        Map<String, List<WikiPageContribution>> contributions;
        RestorePlan plan;
        if (target.getOperationType() == OperationType.lint) {
            LintRestorePlanner.Context context = lintRestorePlanner.plan(target);
            contributions = context.contributions();
            plan = context.plan();
        } else {
            contributions = previewService.loadContributions(excluded);
            plan = planner.plan(excluded, contributions);
        }

        // 미리보기 이후 대상이 바뀌었으면 실행하지 않는다. 되돌리기는 무를 수 없다.
        if (!tokenSigner.matches(previewToken, operationId, contributions)) {
            throw new RestorePreviewStaleException();
        }

        // 반영 전에 확인한다. 뒤에서 걸리면 이미 DB가 바뀐 뒤라 되돌릴 수 없다.
        PageRestorePlan sourcePage = validator.requireApplicable(target, plan);

        Instant now = Instant.now();
        OperationLog restore = lifecycle.start(target, manifestJson(plan), now);

        // applying 커밋 이후 반영·통지 중 실패하면 여기서 실패로 확정하고 원래 예외를 그대로 던진다.
        // 그렇지 않으면 lifecycle.finish()가 실행되지 않아 이 작업이 applying에 영구히 남는다.
        boolean notified;
        try {
            applier.apply(restore, plan, excluded, contributions, now);
            notified = notify(restore, target, excluded, plan, sourcePage);
        } catch (RuntimeException e) {
            // 예외 원문은 내부 정보(스키마·호스트 등)를 담을 수 있어 API로 노출되는 summary에는 싣지 않는다.
            log.error("[복구 반영 실패] operationId={}", restore.getOperationId(), e);
            lifecycle.fail(restore.getOperationId(), "복구 반영 중 오류가 발생했습니다.", now);
            throw e;
        }
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

    /**
     * llmPipeline에 재작성을 맡긴다. ingest와 lint가 요청 스키마가 달라 엔드포인트도 나뉜다.
     *
     * <p>통지 재시도 잡({@link RestoreNotifyRetryJob})도 그대로 재사용하므로 패키지 밖으로는 열지 않는다.
     */
    boolean notify(OperationLog restore, OperationLog target, Set<String> excluded,
                   RestorePlan plan, PageRestorePlan sourcePage) {
        String callbackUrl = callbackBaseUrl + "/api/ai-operations/"
                + restore.getOperationId() + "/result";

        if (target.getOperationType() == OperationType.lint) {
            return restoreRequester.sendLintRestore(new PipelineRestoreRequester.LintRestoreRun(
                    restore.getOperationId(), restore.getWorkspaceId(), callbackUrl,
                    target.getOperationId(),
                    PipelineRestoreRequester.rebuildPages(plan),
                    PipelineRestoreRequester.deletedPages(plan)));
        }

        // ingest는 원문 문서를 대표하는 source page를 별도 필드로 넘긴다.
        return restoreRequester.sendIngestRestore(new PipelineRestoreRequester.IngestRestoreRun(
                restore.getOperationId(), restore.getWorkspaceId(), callbackUrl,
                sourcePage.targetOperationId(),
                List.copyOf(excluded),
                new PipelineRestoreRequester.IngestRestoreRun.SourcePage(sourcePage.pageId()),
                PipelineRestoreRequester.rebuildPages(plan),
                PipelineRestoreRequester.deletedPagesExcept(plan, sourcePage.pageId())));
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
