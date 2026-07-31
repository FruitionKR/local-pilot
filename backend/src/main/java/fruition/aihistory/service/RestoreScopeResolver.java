package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.domain.RestoreMode;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기준 작업과 {@link RestoreMode}로 제외할 작업 집합을 정한다.
 *
 * <p>ingest만 모은다. lint와 restore는 기여를 만들지 않아 판정에 영향이 없다.
 */
@Component
public class RestoreScopeResolver {

    private final OperationLogRepository operationLogRepository;

    public RestoreScopeResolver(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public Set<String> resolve(OperationLog target, RestoreMode mode) {
        return switch (mode) {
            // 이 작업 하나만
            case single -> Set.of(target.getOperationId());

            // 기준 작업 이후만. 기준 작업 자신은 살린다.
            // 같은 시각의 작업이 섞이는 것을 막기 위해 기준 작업 id는 명시적으로 뺀다.
            case since -> {
                Set<String> ids = toIds(operationLogRepository.findByTargetDocumentAfter(
                        requireTargetDocument(target, mode),
                        target.getCreatedAt(),
                        OperationType.ingest));
                ids.remove(target.getOperationId());
                yield ids;
            }

            // 그 문서의 작업 전부. 기준 작업도 포함한다.
            case document -> toIds(
                    operationLogRepository.findByTargetDocumentIdAndOperationTypeOrderByCreatedAtAsc(
                            requireTargetDocument(target, mode), OperationType.ingest));
        };
    }

    private Set<String> toIds(List<OperationLog> logs) {
        return logs.stream()
                .map(OperationLog::getOperationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String requireTargetDocument(OperationLog target, RestoreMode mode) {
        String documentId = target.getTargetDocumentId();
        if (documentId == null) {
            throw new InvalidRestoreRequestException(
                    "이 작업은 원문 문서에 속하지 않아 mode=" + mode + "로 복구할 수 없습니다. single만 사용할 수 있습니다.");
        }
        return documentId;
    }
}
