package fruition.core.document.repository;

import fruition.core.document.domain.AiCommandOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiCommandOutboxRepository extends JpaRepository<AiCommandOutbox, String> {

    List<AiCommandOutbox> findTop100ByOrderByCreatedAtAsc();
}
