package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.document.repository.DocumentRepository;
import fruition.skill.domain.Skill;
import fruition.skill.domain.SkillScope;
import fruition.skill.domain.SkillVersion;
import fruition.skill.exception.SkillNotFoundException;
import fruition.skill.repository.SkillRepository;
import fruition.skill.repository.SkillVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillExecutionResolverTest {
    @Mock SkillRepository skillRepository;
    @Mock SkillVersionRepository versionRepository;
    @Mock DocumentRepository documentRepository;

    private SkillExecutionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SkillExecutionResolver(
                skillRepository, versionRepository, documentRepository, new ObjectMapper());
    }

    @Test
    void explicitCommandSelectsLatestVersionAndRemovesCommandFromMessage() {
        Skill skill = skill("meeting-summary");
        SkillVersion version = version(skill);
        when(skillRepository.findAccessibleByCommand(
                "ws_1", "user_1", "meeting-summary", PageRequest.of(0, 1)))
                .thenReturn(List.of(skill));
        when(versionRepository.findFirstBySkillIdOrderByVersionDesc(skill.getId()))
                .thenReturn(Optional.of(version));

        var plan = resolver.resolve("ws_1", "user_1", "/meeting-summary 오늘 회의를 정리해줘");

        assertThat(plan.mode()).isEqualTo("explicit");
        assertThat(plan.message()).isEqualTo("오늘 회의를 정리해줘");
        assertThat(plan.selectedSkill().skillId()).isEqualTo(skill.getId());
        assertThat(plan.selectedSkill().versionId()).isEqualTo(version.getId());
        assertThat(plan.skillCandidates()).isEmpty();
    }

    @Test
    void naturalMessageUsesAtMostTwentyAutoRoutingCandidatesQuery() {
        when(skillRepository.findAutoRoutingCandidates("ws_1", "user_1", PageRequest.of(0, 20)))
                .thenReturn(List.of());

        var plan = resolver.resolve("ws_1", "user_1", "회의를 정리해줘");

        assertThat(plan.mode()).isEqualTo("auto");
        assertThat(plan.message()).isEqualTo("회의를 정리해줘");
        assertThat(plan.skillCandidates()).isEmpty();
    }

    @Test
    void unknownExplicitCommandIsRejected() {
        when(skillRepository.findAccessibleByCommand(
                "ws_1", "user_1", "missing", PageRequest.of(0, 1)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve("ws_1", "user_1", "/missing 실행해줘"))
                .isInstanceOf(SkillNotFoundException.class);
    }

    private Skill skill(String command) {
        return new Skill("ws_1", "user_1", SkillScope.personal, command);
    }

    private SkillVersion version(Skill skill) {
        return new SkillVersion(skill.getId(), 2, "회의 정리", "회의 요약", "회의를 정리한다.",
                "[]", "[]", "[]", "{}", "hash", "user_1");
    }
}
