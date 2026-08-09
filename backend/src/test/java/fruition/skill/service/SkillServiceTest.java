package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.skill.dto.SkillAuthoringRequest;
import fruition.skill.dto.SkillDraftFromRunsRequest;
import fruition.skill.dto.SkillPublishRequest;
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
    @Mock SkillDraftSourceLoader sourceLoader;
    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(memberRepository, requester, sourceLoader);
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

    @Test
    void draftFromRuns_loadsAuthorizedCompletedSources() throws Exception {
        SkillDraftFromRunsRequest request = new SkillDraftFromRunsRequest(
                "personal", List.of("run_1"), List.of("간결하게 작성"));
        SkillDraftSourceLoader.LoadedSources sources = new SkillDraftSourceLoader.LoadedSources(
                List.of(new SkillDraftSourceLoader.SourceRun("run_1", "completed", "요청", "계획",
                        List.of(new SkillDraftSourceLoader.SourceOperation("create_document", "문서 작성")))),
                List.of("run_1", "doc_1"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(sourceLoader.load("ws_1", "user_1", request.sourceRunIds())).thenReturn(sources);

        service.draftFromRuns("ws_1", "user_1", request);

        verify(requester).draftFromRuns("ws_1", "user_1", request, sources);
    }

    @Test
    void publish_linksSourcesOnlyAfterPublishedResponse() throws Exception {
        SkillPublishRequest request = new SkillPublishRequest(
                "personal", "meeting-notes", "회의록", "# 작성 절차", List.of("run_1"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(requester.publish("ws_1", "user_1", request)).thenReturn(
                new ObjectMapper().readTree("{\"status\":\"published\",\"version_id\":\"version_1\"}"));

        service.publish("ws_1", "user_1", request);

        verify(sourceLoader).load("ws_1", "user_1", List.of("run_1"));
        verify(sourceLoader).linkPublishedVersion("version_1", "ws_1", "user_1", List.of("run_1"));
    }

    private SkillAuthoringRequest request(String mode, String instruction, List<String> references) {
        return new SkillAuthoringRequest("personal", "meeting-notes", null, instruction, mode, references);
    }
}
