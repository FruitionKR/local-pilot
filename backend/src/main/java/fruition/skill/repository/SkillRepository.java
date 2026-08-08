package fruition.skill.repository;

import fruition.skill.domain.Skill;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, String> {
    @Query("""
            SELECT s FROM Skill s
            WHERE s.workspaceId = :workspaceId AND s.deletedAt IS NULL
              AND (s.scope = fruition.skill.domain.SkillScope.team OR s.ownerUserId = :userId)
            ORDER BY CASE WHEN s.scope = fruition.skill.domain.SkillScope.team THEN 0 ELSE 1 END, s.command
            """)
    List<Skill> findAccessible(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Query("""
            SELECT s FROM Skill s
            WHERE s.id = :skillId AND s.workspaceId = :workspaceId AND s.deletedAt IS NULL
              AND (s.scope = fruition.skill.domain.SkillScope.team OR s.ownerUserId = :userId)
            """)
    Optional<Skill> findAccessibleById(@Param("workspaceId") String workspaceId,
                                       @Param("userId") String userId,
                                       @Param("skillId") String skillId);

    @Query("""
            SELECT s FROM Skill s
            WHERE s.workspaceId = :workspaceId AND s.command = :command AND s.deletedAt IS NULL
              AND (s.scope = fruition.skill.domain.SkillScope.team OR s.ownerUserId = :userId)
            """)
    Optional<Skill> findAccessibleByCommand(@Param("workspaceId") String workspaceId,
                                            @Param("userId") String userId,
                                            @Param("command") String command);

    @Query("""
            SELECT s FROM Skill s
            WHERE s.workspaceId = :workspaceId AND s.deletedAt IS NULL AND s.autoRoutingEnabled = true
              AND (s.scope = fruition.skill.domain.SkillScope.team OR s.ownerUserId = :userId)
            ORDER BY s.updatedAt DESC, s.id ASC
            """)
    List<Skill> findAutoRoutingCandidates(@Param("workspaceId") String workspaceId,
                                          @Param("userId") String userId,
                                          Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Skill s WHERE s.id = :skillId AND s.workspaceId = :workspaceId AND s.deletedAt IS NULL")
    Optional<Skill> findActiveForUpdate(@Param("workspaceId") String workspaceId,
                                        @Param("skillId") String skillId);

    @Query("""
            SELECT COUNT(s) > 0 FROM Skill s
            WHERE s.workspaceId = :workspaceId AND s.command = :command AND s.deletedAt IS NULL
              AND s.id <> :excludedId
              AND (:teamScope = true OR s.scope = fruition.skill.domain.SkillScope.team OR s.ownerUserId = :userId)
            """)
    boolean commandExists(@Param("workspaceId") String workspaceId, @Param("userId") String userId,
                          @Param("command") String command, @Param("teamScope") boolean teamScope,
                          @Param("excludedId") String excludedId);

    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))", nativeQuery = true)
    void lockCommand(@Param("lockKey") String lockKey);
}
