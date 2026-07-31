package fruition.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.exception.InvalidCallbackPayloadException;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.exception.OperationPayloadConflictException;
import fruition.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * llmPipeline이 보낸 ingest 결과를 받는다.
 *
 * <pre>
 * ① 멱등     payload_hash 비교 → 같으면 기존 결과, 다르면 409
 * ② 정합성   등록값과 콜백값 일치 확인
 * ③ 읽기     [트랜잭션 밖] key 검증 후 본문을 읽고 hash 대조
 * ④ 적재     {@link OperationApplier}가 한 트랜잭션으로 처리
 * </pre>
 *
 * <p>③을 트랜잭션 밖에 두는 이유는 페이지 여러 개를 저장소에서 읽는 동안 DB 커넥션을
 * 붙잡지 않기 위해서다.
 */
@Service
public class OperationIngestService {

    private final OperationLogRepository operationLogRepository;
    private final WikiObjectReader objectReader;
    private final OperationApplier applier;
    private final ObjectMapper objectMapper;

    public OperationIngestService(OperationLogRepository operationLogRepository,
                                  WikiObjectReader objectReader,
                                  OperationApplier applier,
                                  ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.objectReader = objectReader;
        this.applier = applier;
        this.objectMapper = objectMapper;
    }

    /** @return 실제로 만든 변경내역 수 */
    public int accept(String operationId, OperationResultRequest request) {
        if (!operationId.equals(request.operationId())) {
            throw new InvalidCallbackPayloadException(
                    "경로와 본문의 operation_id가 다릅니다: " + operationId);
        }

        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        String payloadHash = hash(request);

        // 이미 확정된 작업이면 같은 payload인지만 본다. 같으면 재전송이므로 기존 결과를 돌려준다.
        if (operation.getStatus().isTerminal()) {
            if (payloadHash.equals(operation.getPayloadHash())) {
                return operation.getChangedResourceCount();
            }
            throw new OperationPayloadConflictException(operationId);
        }

        verifyMatchesRegistration(operation, request);

        List<OperationApplier.LoadedPage> loaded = request.changedPages().stream()
                .map(page -> load(operation, page))
                .toList();

        return applier.apply(operationId, request, loaded, payloadHash, Instant.now());
    }

    private OperationApplier.LoadedPage load(OperationLog operation,
                                             OperationResultRequest.ChangedPage page) {
        String markdown = objectReader.read(page.markdownKey(), operation.getWorkspaceId(),
                page.pageId(), operation.getOperationId());
        String actualHash = objectReader.sha256(markdown);
        if (!actualHash.equals(page.contentHash())) {
            throw new InvalidCallbackPayloadException(
                    "본문 해시가 일치하지 않습니다: pageId=" + page.pageId());
        }
        String contributionKey = page.contributionKey() != null
                ? page.contributionKey()
                : objectReader.contributionKey(operation.getWorkspaceId(), page.pageId(),
                        operation.getOperationId());
        return new OperationApplier.LoadedPage(
                page.pageId(), page.markdownKey(), contributionKey, markdown, actualHash);
    }

    /** 콜백이 보낸 값은 권한 근거가 아니라 대조용이다. 생략하면 대조하지 않는다. */
    private void verifyMatchesRegistration(OperationLog operation, OperationResultRequest request) {
        if (mismatched(operation.getWorkspaceId(), request.workspaceId())
                || mismatched(operation.getUserId(), request.userId())
                || mismatched(operation.getTargetDocumentId(), request.targetDocumentId())) {
            throw new InvalidCallbackPayloadException(
                    "요청 등록 정보와 결과가 일치하지 않습니다: operationId=" + operation.getOperationId());
        }
    }

    private boolean mismatched(String registered, String reported) {
        return reported != null && !reported.equals(registered);
    }

    /** 같은 결과가 다시 오면 같은 값이 나와야 한다. */
    private String hash(OperationResultRequest request) {
        try {
            String canonical = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("payload_hash를 계산하지 못했습니다.", e);
        }
    }
}
