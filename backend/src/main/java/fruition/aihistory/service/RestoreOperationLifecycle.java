package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * 복구 작업의 시작과 종료를 각각 별도 트랜잭션으로 커밋한다.
 *
 * <p>{@link RestoreExecuteService}와 분리한 이유는 자기 호출로는 {@code @Transactional}이
 * 걸리지 않기 때문이다.
 */
@Component
public class RestoreOperationLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RestoreOperationLifecycle.class);

    private final OperationLogRepository operationLogRepository;

    public RestoreOperationLifecycle(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    /**
     * 복구 작업을 {@code applying}으로 먼저 커밋한다. 반영 중 실패해도 그 상태로 남아
     * 같은 {@code restore_manifest}로 재시도할 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OperationLog start(OperationLog target, String manifestJson, Instant now) {
        String operationId = "op_" + randomSuffix();
        return operationLogRepository.save(OperationLog.applying(
                operationId, target.getWorkspaceId(), target.getUserId(),
                target.getTargetDocumentId(), target.getOperationId(), manifestJson, now));
    }

    /**
     * 통지 결과에 따라 상태를 확정한다.
     *
     * <p>재작성 대상이 없고 통지도 됐으면 완료다. 대상이 있으면 llmPipeline 결과를 기다리는
     * {@code rebuilding}이고, 통지 자체가 실패하면 {@code notify_pending}으로 남겨 재시도 대상으로 둔다.
     * 복구는 이미 DB에 반영됐고 재작성만 보류된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String restoreOperationId, RestorePlan plan, boolean notified, Instant now) {
        operationLogRepository.findById(restoreOperationId).ifPresent(restore -> {
            if (!notified) {
                log.warn("[복구 통지 보류] operationId={} 재작성 {}건이 대기 중입니다.",
                        restoreOperationId, plan.rebuildCount());
                restore.moveTo(OperationStatus.notify_pending);
            } else if (plan.hasRebuild()) {
                restore.moveTo(OperationStatus.rebuilding);
            } else {
                restore.complete(OperationStatus.succeeded, summary(plan), plan.pages().size(), null, now);
            }
        });
    }

    private String summary(RestorePlan plan) {
        return "삭제 " + plan.deleteCount() + "건 · 복원 " + plan.restoreCount()
                + "건 · 재작성 " + plan.rebuildCount() + "건";
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
