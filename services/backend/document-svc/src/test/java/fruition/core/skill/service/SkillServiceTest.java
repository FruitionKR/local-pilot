package fruition.core.skill.service;

import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.skill.dto.SkillAuthoringRequest;
import fruition.core.skill.repository.PipelineSkillRequester;
import fruition.shared.ai.AiModelCatalog;
import fruition.shared.ai.InvalidAiModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock PipelineSkillRequester requester;
    @Mock AiModelCatalog aiModelCatalog;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(workspaceAccessGuard, requester, aiModelCatalog);
    }

    @Test
    void author_checksMembershipThenForwardsPathAndPrincipalScope() {
        var request = new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of("doc_1"),
                "openai", "gpt-5-nano");

        service.author("ws_1", "user_1", request);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        verify(aiModelCatalog).resolve("openai", "gpt-5-nano");
        verify(requester).author("ws_1", "user_1", request);
    }

    @Test
    void author_rejectsUnsupportedModelBeforePipelineCall() {
        var request = new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of(),
                "openai", "legacy-model");
        doThrow(new InvalidAiModelException("선택할 수 없는 AI 모델입니다."))
                .when(aiModelCatalog).resolve("openai", "legacy-model");

        assertThatThrownBy(() -> service.author("ws_1", "user_1", request))
                .isInstanceOf(InvalidAiModelException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void author_failsClosedBeforePipelineCall() {
        var request = new SkillAuthoringRequest(
                "personal", "meeting-notes", null,
                "회의록 Skill을 만들어줘", "enhance", List.of(),
                "openai", "gpt-5-nano");
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.author("ws_1", "user_2", request))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
    }
}
