package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.repository.OperationLogRepository;
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
     * 통지 결과에 따라 상태를 옮긴다.
     *
     * <p>Wiki 복구는 <b>재작성 대상이 없어도</b> 여기서 끝내지 않는다. llmPipeline이 지시서를 받으면
     * 재작성할 페이지가 없어도 링크·임베딩을 정리한 뒤 반드시 결과를 보내오기 때문이다. 미리 완료로
     * 확정하면 그 콜백이 종료된 작업에 도착해 409로 거절되고, 정리가 끝나기도 전에 사용자에게
     * 완료로 보인다. 확정은 재조립 결과를 받을 때 한다.
     *
     * <p>통지 자체가 실패하면 {@code notify_pending}으로 남겨 재시도 대상으로 둔다. 복구는 이미
     * DB에 반영됐고 llmPipeline 몫만 보류된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String restoreOperationId, RestorePlan plan, boolean notified, Instant now) {
        operationLogRepository.findById(restoreOperationId).ifPresent(restore -> {
            if (notified) {
                restore.moveTo(OperationStatus.rebuilding, summary(plan));
            } else {
                log.warn("[복구 통지 보류] operationId={} 재작성 {}건이 대기 중입니다.",
                        restoreOperationId, plan.rebuildCount());
                restore.moveTo(OperationStatus.notify_pending, summary(plan));
            }
        });
    }

    /**
     * 반영({@code apply})이나 통지 준비 중 실패하면 여기서 종결 상태로 확정한다.
     *
     * <p>{@link #start}와 마찬가지로 REQUIRES_NEW라, 실패를 일으킨 트랜잭션이 롤백되어도
     * 이 실패 확정은 그대로 커밋된다. 이게 없으면 반영 실패가 {@code applying}에 영구히
     * 머물러 재시도할 방법이 없다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String restoreOperationId, String reason, Instant now) {
        operationLogRepository.findById(restoreOperationId).ifPresent(restore -> {
            log.warn("[복구 실패 확정] operationId={} reason={}", restoreOperationId, reason);
            restore.complete(OperationStatus.failed, reason,
                    restore.getChangedResourceCount(), null, now);
        });
    }

    /** 문서 되돌리기는 재작성이 없어 반영이 끝나면 그 자리에서 확정된다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishDocument(String restoreOperationId, long toVersion, long newVersion, Instant now) {
        operationLogRepository.findById(restoreOperationId).ifPresent(restore ->
                restore.complete(OperationStatus.succeeded,
                        "버전 " + toVersion + " 내용으로 되돌렸습니다. 새 버전 " + newVersion,
                        1, null, now));
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
