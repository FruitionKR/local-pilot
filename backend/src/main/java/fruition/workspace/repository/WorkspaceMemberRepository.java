package fruition.workspace.repository;

import fruition.workspace.domain.Workspace;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.domain.WorkspaceMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    boolean existsByWorkspace_IdAndUser_Id(String workspaceId, String userId);

    Optional<WorkspaceMember> findByWorkspace_IdAndUser_Id(String workspaceId, String userId);

    @Query("SELECT m.workspace FROM WorkspaceMember m WHERE m.user.id = :userId ORDER BY m.workspace.createdAt DESC")
    List<Workspace> findAllWorkspacesByUserId(@Param("userId") String userId);
}
