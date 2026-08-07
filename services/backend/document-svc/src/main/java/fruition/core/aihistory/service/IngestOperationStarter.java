package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * ingest 요청을 AI 작업 로그에 먼저 등록한다.
 *
 * <p>llmPipeline을 <b>호출하기 전에</b> {@code processing}으로 커밋해야 한다. 콜백이 도착했을 때
 * 대조할 등록값이 없으면 결과를 받아들일 수 없다.
 *
 * <p>llmPipeline의 {@code PipelineRunIn}이 {@code extra="forbid"}라, 스키마가 준비되기 전에
 * {@code operation_id}를 보내면 422가 난다. 그래서 기본값을 꺼두고 llmPipeline이 준비되면 켠다.
 */
@Component
public class IngestOperationStarter {

    private final OperationLogRepository operationLogRepository;
    private final boolean enabled;
    private final String callbackBaseUrl;

    public IngestOperationStarter(OperationLogRepository operationLogRepository,
                                  @Value("${app.aihistory.ingest-logging-enabled:false}") boolean enabled,
                                  @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.operationLogRepository = operationLogRepository;
        this.enabled = enabled;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 진행 중 작업을 별도 트랜잭션으로 커밋한다. 호출 측 트랜잭션이 롤백돼도 등록은 남아야
     * 콜백이 도착했을 때 대조할 수 있다.
     *
     * @return 켜져 있으면 발급한 작업 식별자
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<String> start(String workspaceId, String userId, String documentId) {
        if (!enabled) {
            return Optional.empty();
        }
        String operationId = "op_" + randomSuffix();
        operationLogRepository.save(OperationLog.processing(
                operationId, workspaceId, userId, OperationType.ingest, documentId, Instant.now()));
        return Optional.of(operationId);
    }

    /** llmPipeline 호출 자체가 실패한 경우. 진행 중으로 남지 않게 확정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String operationId, String reason) {
        operationLogRepository.findById(operationId).ifPresent(operation ->
                operation.complete(OperationStatus.failed, reason, 0, null, Instant.now()));
    }

    public String resultCallbackUrl(String operationId) {
        return callbackBaseUrl + "/api/ai-operations/" + operationId + "/result";
    }

    private String randomSuffix() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
