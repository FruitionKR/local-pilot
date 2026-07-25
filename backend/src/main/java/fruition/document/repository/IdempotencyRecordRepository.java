package fruition.document.repository;

import fruition.document.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndEndpointScopeAndIdempotencyKey(
            String userId,
            String endpointScope,
            String idempotencyKey
    );
}
