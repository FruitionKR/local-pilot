package fruition.access.workspace.repository;

import fruition.access.workspace.domain.WorkspaceInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, String> {

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByWorkspaceIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
            String workspaceId, String email);

    List<WorkspaceInvitation> findByWorkspaceIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAt(
            String workspaceId);
}
