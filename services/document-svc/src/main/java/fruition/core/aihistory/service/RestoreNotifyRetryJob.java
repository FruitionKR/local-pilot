package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * {@code notify_pending}으로 남은 복구 통지를 주기적으로 재시도한다.
 *
 * <p>llmPipeline 전송({@code PipelineRestoreRequester.post})은 실패해도 예외를 던지지 않고
 * 상태만 {@code notify_pending}으로 남긴다({@link RestoreOperationLifecycle#finish} 참고).
 * 그 상태를 아무도 회수하지 않으면 재작성 통지가 영구히 보류된다.
 *
 * <p>DB에는 재시도 횟수를 세는 컬럼이 없다. 새 컬럼을 만드는 대신 작업이 시작된
 * {@code createdAt}으로부터 {@link #MAX_RETRY_WINDOW}가 지났는지로 상한을 둔다.
 * 그 기간을 넘기면 더 재시도하지 않고 {@code failed}로 확정한다.
 *
 * <p>인스턴스가 여러 대면 같은 작업을 동시에 재시도해 통지를 중복으로 보낼 수 있다.
 * 분산 락은 별도로 갖추지 않았다.
 */
@Component
public class RestoreNotifyRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RestoreNotifyRetryJob.class);

    private static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L;
    private static final Duration MAX_RETRY_WINDOW = Duration.ofHours(24);
    private static final int BATCH_SIZE = 200;

    private final OperationLogRepository operationLogRepository;
    private final RestoreOperationLifecycle lifecycle;
    private final RestoreExecuteService restoreExecuteService;
    private final RestoreScopeResolver scopeResolver;
    private final RestoreTargetValidator validator;
    private final ObjectMapper objectMapper;

    public RestoreNotifyRetryJob(OperationLogRepository operationLogRepository,
                                 RestoreOperationLifecycle lifecycle,
                                 RestoreExecuteService restoreExecuteService,
                                 RestoreScopeResolver scopeResolver,
                                 RestoreTargetValidator validator,
                                 ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.lifecycle = lifecycle;
        this.restoreExecuteService = restoreExecuteService;
        this.scopeResolver = scopeResolver;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = RETRY_INTERVAL_MS)
    public void retry() {
        Instant now = Instant.now();
        for (OperationLog restore : operationLogRepository.findByStatus(
                OperationStatus.notify_pending, PageRequest.of(0, BATCH_SIZE))) {
            retryOne(restore, now);
        }
    }

    private void retryOne(OperationLog restore, Instant now) {
        if (Duration.between(restore.getCreatedAt(), now).compareTo(MAX_RETRY_WINDOW) > 0) {
            log.warn("[복구 통지 재시도 포기] operationId={} 최대 재시도 기간을 넘겼습니다.",
                    restore.getOperationId());
            lifecycle.fail(restore.getOperationId(),
                    "llmPipeline 통지를 재시도 기간 내에 보내지 못했습니다.", now);
            return;
        }

        OperationLog target = restore.getRestoredFrom() == null ? null
                : operationLogRepository.findById(restore.getRestoredFrom()).orElse(null);
        if (target == null || restore.getRestoreManifest() == null) {
            log.warn("[복구 통지 재시도 불가] operationId={} 원본 작업 또는 지시서를 찾을 수 없습니다.",
                    restore.getOperationId());
            lifecycle.fail(restore.getOperationId(),
                    "복구 지시서 또는 원본 작업을 찾을 수 없습니다.", now);
            return;
        }

        try {
            RestorePlan plan = objectMapper.readValue(restore.getRestoreManifest(), RestorePlan.class);
            boolean isLint = target.getOperationType() == OperationType.lint;

            // ingest만 취소 대상 집합과 source page가 필요하다. lint는 target 하나만 되돌린다.
            //
            // 주의: excluded는 지금 다시 계산한 값이다. 원래 반영 때 쓴 집합을 그대로 보관하지
            // 않기 때문이다. 재시도 사이에 같은 문서로 새 ingest가 들어오면 재계산한 집합이
            // 실제로 DB에 반영된 것과 달라질 수 있다(취소 목록에 아직 반영 안 된 작업이 섞임).
            // restore_manifest에 excluded를 별도로 저장하는 스키마 변경 없이는 완전히 막을 수
            // 없는 근사치다.
            Set<String> excluded = isLint ? Set.of() : scopeResolver.resolve(target);
            PageRestorePlan sourcePage = isLint ? null : validator.requireApplicable(target, plan);

            boolean notified = restoreExecuteService.notify(restore, target, excluded, plan, sourcePage);
            if (notified) {
                lifecycle.finish(restore.getOperationId(), plan, true, now);
            }
            // 여전히 실패하면 notify_pending 그대로 두고 다음 주기에 다시 시도한다.
        } catch (Exception e) {
            log.warn("[복구 통지 재시도 실패] operationId={} error={}",
                    restore.getOperationId(), e.getMessage());
        }
    }
}
