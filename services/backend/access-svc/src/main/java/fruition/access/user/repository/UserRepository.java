package fruition.access.user.repository;

import fruition.access.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmailAndProvider(String email, String provider);

    List<User> findAllByEmail(String email);

    boolean existsByEmailAndProvider(String email, String provider);
}
