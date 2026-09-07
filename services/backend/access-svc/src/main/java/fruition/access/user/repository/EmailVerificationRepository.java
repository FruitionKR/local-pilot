package fruition.access.user.repository;

import fruition.access.user.domain.EmailVerification;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    List<EmailVerification> findByEmailAndPurposeAndConsumedAtIsNull(String email, String purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, String purpose, Instant since);

    Optional<EmailVerification> findFirstByEmailAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(
            String email, String purpose, Instant since);

    // 소비 트랜잭션이 끝날 때까지 같은 토큰의 검증·소비를 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
