package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.repository.OperationLogRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기준 작업으로 제외할 작업 집합을 정한다.
 *
 * <p>복구는 <b>"이 시점으로 되돌리기" 하나</b>다. 기준 작업 이후 같은 문서의 작업을 전부 걷어내며,
 * 그사이에 만들어진 source page와 concept page는 받치는 기여가 사라져 삭제된다.
 * 사용자가 범위를 고르지 않는다.
 *
 * <p>ingest만 모은다. lint와 restore는 기여를 만들지 않아 판정에 영향이 없다.
 */
@Component
public class RestoreScopeResolver {

    private final OperationLogRepository operationLogRepository;

    public RestoreScopeResolver(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public Set<String> resolve(OperationLog target) {
        // lint는 원문 문서에 속하지 않아 "그 문서의 이후 작업"이라는 범위를 만들 수 없다.
        // 그 작업 하나만 되돌린다.
        if (target.getTargetDocumentId() == null) {
            return Set.of(target.getOperationId());
        }

        Set<String> ids = operationLogRepository.findByTargetDocumentAfter(
                        target.getTargetDocumentId(), target.getCreatedAt(), OperationType.ingest)
                .stream()
                .map(OperationLog::getOperationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 기준 작업 자신은 살린다. 같은 시각의 작업이 섞이는 것을 막기 위해 명시적으로 뺀다.
        ids.remove(target.getOperationId());
        return ids;
    }
}
