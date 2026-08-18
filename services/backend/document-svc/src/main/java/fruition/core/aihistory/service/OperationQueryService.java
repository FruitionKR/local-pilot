package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.OperationLogDetailResponse;
import fruition.core.aihistory.dto.OperationLogListResponse;
import fruition.core.aihistory.exception.InvalidRestoreRequestException;
import fruition.core.aihistory.exception.OperationNotFoundException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 작업 로그 조회. 목록과 상세 모두 저장된 값만 읽고 diff를 계산하지 않는다.
 */
@Service
public class OperationQueryService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    /** 첫 페이지용 기본 커서. null을 넘기면 Postgres가 파라미터 타입을 추론하지 못한다. */
    private static final Instant NO_CURSOR = Instant.parse("9999-12-31T23:59:59Z");

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final ChangeDiffLoader diffLoader;
    private final ObjectMapper objectMapper;

    public OperationQueryService(OperationLogRepository operationLogRepository,
                                 OperationChangeRepository operationChangeRepository,
                                 WorkspaceAccessGuard workspaceAccessGuard,
                                 ChangeDiffLoader diffLoader,
                                 ObjectMapper objectMapper) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.diffLoader = diffLoader;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public OperationLogListResponse list(String workspaceId, String userId,
                                         String type, String status, String cursor, Integer size) {
        verifyMember(workspaceId, userId);

        int limit = normalizeSize(size);
        List<OperationLog> found = operationLogRepository.findPage(
                workspaceId, parseType(type), parseStatus(status), parseCursor(cursor),
                OperationType.document_edit, OperationStatus.succeeded,
                PageRequest.of(0, limit + 1));

        // 한 건 더 읽어 다음 페이지가 있는지 본다.
        boolean hasMore = found.size() > limit;
        List<OperationLog> page = hasMore ? found.subList(0, limit) : found;
        String nextCursor = hasMore ? page.get(page.size() - 1).getCreatedAt().toString() : null;

        return new OperationLogListResponse(
                page.stream().map(OperationLogListResponse.Item::from).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public OperationLogDetailResponse detail(String workspaceId, String userId, String operationId) {
        verifyMember(workspaceId, userId);
        OperationLog log = operationLogRepository
                .findByOperationIdAndWorkspaceId(operationId, workspaceId)
                .orElseThrow(() -> new OperationNotFoundException(operationId));
        // 상세를 한 번 부르면 변경분까지 다 받도록 여기서 계산한다.
        List<OperationChange> found = operationChangeRepository.findByOperationIdOrderByIdAsc(operationId);
        List<ChangeDiffLoader.Diff> diffs = diffLoader.load(found);

        List<OperationLogDetailResponse.Change> changes = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            changes.add(OperationLogDetailResponse.Change.from(found.get(i), diffs.get(i)));
        }
        return OperationLogDetailResponse.from(log, changes, objectMapper);
    }

    private void verifyMember(String workspaceId, String userId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private OperationType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return OperationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new InvalidRestoreRequestException("알 수 없는 작업 유형입니다: " + type);
        }
    }

    private OperationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return OperationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidRestoreRequestException("알 수 없는 상태입니다: " + status);
        }
    }

    /** 커서는 마지막으로 받은 항목의 {@code created_at}이다. */
    private Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return NO_CURSOR;
        }
        try {
            return Instant.parse(cursor);
        } catch (DateTimeParseException e) {
            throw new InvalidRestoreRequestException("커서 형식이 올바르지 않습니다: " + cursor);
        }
    }
}
