package fruition.aihistory.repository;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationLogRepository extends JpaRepository<OperationLog, String> {

    Optional<OperationLog> findByOperationIdAndWorkspaceId(String operationId, String workspaceId);

    /**
     * 복구 대상 작업을 고른다. 기준 작업 이후 같은 문서의 작업 전부(mode=since)를 얻을 때 쓴다.
     * mode=document면 {@code after}에 아주 이른 시각을 넣는다.
     */
    @Query("""
            SELECT l FROM OperationLog l
            WHERE l.targetDocumentId = :targetDocumentId
              AND l.createdAt > :after
              AND l.operationType = :operationType
            ORDER BY l.createdAt
            """)
    List<OperationLog> findByTargetDocumentAfter(
            @Param("targetDocumentId") String targetDocumentId,
            @Param("after") Instant after,
            @Param("operationType") OperationType operationType
    );

    /** mode=document에서 그 문서의 작업 전부를 얻을 때 쓴다. */
    List<OperationLog> findByTargetDocumentIdAndOperationTypeOrderByCreatedAtAsc(
            String targetDocumentId, OperationType operationType);
}
