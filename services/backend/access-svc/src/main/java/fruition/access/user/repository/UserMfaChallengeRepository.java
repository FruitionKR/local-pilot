package fruition.access.user.repository;

import fruition.access.user.domain.UserMfaChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMfaChallengeRepository extends JpaRepository<UserMfaChallenge, String> {

    Optional<UserMfaChallenge> findByTokenHash(String tokenHash);
}
