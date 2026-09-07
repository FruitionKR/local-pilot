package fruition.access.user.repository;

import fruition.access.user.domain.UserMfaChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMfaChallengeRepository extends JpaRepository<UserMfaChallenge, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserMfaChallenge> findByTokenHash(String tokenHash);
}
