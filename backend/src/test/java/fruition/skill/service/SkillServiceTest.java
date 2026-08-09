package fruition.skill.service;

import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.exception.InvalidSkillRequestException;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {
    @Mock WorkspaceMemberRepository memberRepository;
    @Mock PipelineSkillRequester requester;
    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(memberRepository, requester);
    }

    @Test
    void author_forwardsOnlyServerControlledContract() {
        SkillAuthoringRequest request = request("enhance", "회의록 Skill을 만들어줘", List.of("doc_1"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        service.author("ws_1", "user_1", request);

        verify(requester).author("ws_1", "user_1", request);
    }

    @Test
    void author_rejectsLongEnhanceInput() {
        SkillAuthoringRequest request = request("enhance", "가".repeat(4001), List.of());
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        assertThatThrownBy(() -> service.author("ws_1", "user_1", request))
                .isInstanceOf(InvalidSkillRequestException.class);
    }

    @Test
    void author_rejectsDuplicateReferences() {
        SkillAuthoringRequest request = request("preserve", "본문", List.of("doc_1", "doc_1"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        assertThatThrownBy(() -> service.author("ws_1", "user_1", request))
                .isInstanceOf(InvalidSkillRequestException.class);
    }

    private SkillAuthoringRequest request(String mode, String instruction, List<String> references) {
        return new SkillAuthoringRequest("personal", "meeting-notes", null, instruction, mode, references);
    }
}
