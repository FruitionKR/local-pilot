package fruition.access.user.repository;

import fruition.access.user.domain.UserRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    Optional<UserRefreshToken> findByTokenHash(String tokenHash);

    List<UserRefreshToken> findAllByUserIdAndRevokedAtIsNull(String userId);
}
