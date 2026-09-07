package fruition.access.user.repository;

import fruition.access.user.domain.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMfaRepository extends JpaRepository<UserMfa, String> {
}
