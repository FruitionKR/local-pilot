package fruition.skill.repository;

import fruition.skill.domain.SkillVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SkillVersionRepository extends JpaRepository<SkillVersion, String> {
    Optional<SkillVersion> findFirstBySkillIdOrderByVersionDesc(String skillId);
}
