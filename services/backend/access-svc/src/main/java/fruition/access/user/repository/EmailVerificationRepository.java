package fruition.access.user.repository;

import fruition.access.user.domain.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, String purpose);

    List<EmailVerification> findByEmailAndPurposeAndConsumedAtIsNull(String email, String purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, String purpose, Instant since);

    Optional<EmailVerification> findFirstByEmailAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(
            String email, String purpose, Instant since);

    Optional<EmailVerification> findByTokenHash(String tokenHash);
}
