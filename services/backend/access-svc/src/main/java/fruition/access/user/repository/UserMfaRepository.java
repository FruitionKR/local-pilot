package fruition.access.user.repository;

import fruition.access.user.domain.UserMfa;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMfaRepository extends JpaRepository<UserMfa, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM UserMfa m WHERE m.userId = :userId")
    Optional<UserMfa> findForUpdate(@Param("userId") String userId);
}
