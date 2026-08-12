package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

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

    /** 인증된 복구 대상에 같은 토큰 지문이 이미 선점됐는지 확인한다. */
    @Transactional(readOnly = true)
    public boolean isClaimed(String restoredFrom, String restoreTokenHash) {
        return operationLogRepository.existsByOperationTypeAndRestoredFromAndRestoreTokenHash(
                OperationType.restore, restoredFrom, restoreTokenHash);
    }

    /**
     * 복구 작업을 {@code applying}으로 먼저 커밋한다. 반영 중 실패해도 그 상태로 남아
     * 같은 미리보기 토큰의 재실행은 중복 요청으로 거절된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OperationLog> start(OperationLog target, String manifestJson,
                                        String restoreTokenHash, Instant now) {
        String operationId = "op_" + randomSuffix();
        if (operationLogRepository.insertRestoreIfAbsent(
                operationId, target.getWorkspaceId(), target.getUserId(),
                target.getTargetDocumentId(), target.getOperationId(), manifestJson,
                restoreTokenHash, now) == 0) {
            return Optional.empty();
        }
        return operationLogRepository.findById(operationId);
    }

    /** Kafka command outbox와 같은 바깥 트랜잭션에 참여해 queued 복구를 등록한다. */
    @Transactional
    public Optional<OperationLog> startQueued(OperationLog target, String manifestJson,
                                               String restoreTokenHash, Instant now) {
        String operationId = "op_" + randomSuffix();
        if (operationLogRepository.insertRestoreIfAbsent(
                operationId, target.getWorkspaceId(), target.getUserId(),
                target.getTargetDocumentId(), target.getOperationId(), manifestJson,
                restoreTokenHash, now) == 0) {
            return Optional.empty();
        }
        return operationLogRepository.findById(operationId);
    }

    /**
     * Kafka 결과 receipt와 같은 트랜잭션에서 실패 상태를 확정한다.
     */
    @Transactional
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

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
