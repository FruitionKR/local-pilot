package fruition.workspace.repository;

import fruition.workspace.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    List<Workspace> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Workspace> findByIdAndUserId(String id, String userId);
}
