package fruition.core.aihistory.repository;

import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationLogRepository extends JpaRepository<OperationLog, String> {

    Optional<OperationLog> findByOperationIdAndWorkspaceId(String operationId, String workspaceId);

    /**
     * 목록 조회. 최신순이며 {@code cursor}보다 오래된 것만 가져온다.
     *
     * <p>{@code cursor}에 null을 넘기지 않는다. Postgres는 {@code ? IS NULL} 형태에서 timestamp
     * 파라미터의 타입을 추론하지 못해 실행 자체가 실패한다. 첫 페이지는 먼 미래 값을 넘긴다.
     */
    @Query("""
            SELECT l FROM OperationLog l
            WHERE l.workspaceId = :workspaceId
              AND (:type IS NULL OR l.operationType = :type)
              AND (:status IS NULL OR l.status = :status)
              AND l.createdAt < :cursor
            ORDER BY l.createdAt DESC
            """)
    List<OperationLog> findPage(
            @Param("workspaceId") String workspaceId,
            @Param("type") OperationType type,
            @Param("status") OperationStatus status,
            @Param("cursor") Instant cursor,
            Pageable pageable
    );

    /**
     * 복구 대상 작업을 고른다. 기준 작업 이후 같은 문서의 작업을 전부 얻는다.
     *
     * <p>{@code createdAt}만으로는 같은 밀리초에 만들어진 작업이 {@code >} 비교에서 통째로
     * 빠질 수 있다. {@code (createdAt, operationId)} 복합 커서로 동시각 작업까지 결정적으로
     * 가른다.
     */
    @Query("""
            SELECT l FROM OperationLog l
            WHERE l.targetDocumentId = :targetDocumentId
              AND l.operationType = :operationType
              AND (l.createdAt > :after
                   OR (l.createdAt = :after AND l.operationId > :afterOperationId))
            ORDER BY l.createdAt, l.operationId
            """)
    List<OperationLog> findByTargetDocumentAfter(
            @Param("targetDocumentId") String targetDocumentId,
            @Param("after") Instant after,
            @Param("afterOperationId") String afterOperationId,
            @Param("operationType") OperationType operationType
    );

    /** notify_pending 재시도 잡이 회수 대상을 고를 때 쓴다. */
    List<OperationLog> findByStatus(OperationStatus status, Pageable pageable);
}
