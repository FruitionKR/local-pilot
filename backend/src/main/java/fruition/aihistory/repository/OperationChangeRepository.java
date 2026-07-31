package fruition.aihistory.repository;

import fruition.aihistory.domain.OperationChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationChangeRepository extends JpaRepository<OperationChange, Long> {

    /** 로그 상세 화면용. 한 작업이 바꾼 리소스를 기록 순서대로 반환한다. */
    List<OperationChange> findByOperationIdOrderByIdAsc(String operationId);
}
