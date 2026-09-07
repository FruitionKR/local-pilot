package fruition.access.user.repository;

import fruition.access.user.domain.UserMfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserMfaRecoveryCodeRepository extends JpaRepository<UserMfaRecoveryCode, Long> {

    List<UserMfaRecoveryCode> findAllByUserIdAndConsumedAtIsNull(String userId);

    void deleteAllByUserId(String userId);
}
