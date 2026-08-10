package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * ingest 요청을 AI 작업 로그에 먼저 등록한다.
 *
 * <p>AI command outbox와 같은 트랜잭션에 {@code processing}으로 저장한다. 콜백이 도착했을 때
 * 대조할 등록값과 run이 함께 존재해야 한다.
 *
 * <p>문서 상태와 command outbox를 저장하는 바깥 트랜잭션에 참여해 콜백보다 작업 로그가 먼저 커밋되게 한다.
 */
@Component
public class IngestOperationStarter {

    private final OperationLogRepository operationLogRepository;
    private final String callbackBaseUrl;

    public IngestOperationStarter(OperationLogRepository operationLogRepository,
                                  @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.operationLogRepository = operationLogRepository;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    @Transactional
    public String start(String workspaceId, String userId, String documentId) {
        String operationId = "op_" + randomSuffix();
        operationLogRepository.save(OperationLog.processing(
                operationId, workspaceId, userId, OperationType.ingest, documentId, Instant.now()));
        return operationId;
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
