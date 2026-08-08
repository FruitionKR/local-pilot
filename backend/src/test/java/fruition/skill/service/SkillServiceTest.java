package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.exception.TeamSkillForbiddenException;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.domain.WorkspaceRole;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock WorkspaceMemberRepository memberRepository;
    @Mock SkillReferenceDocumentLoader referenceDocumentLoader;
    @Mock PipelineSkillRequester requester;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(memberRepository, referenceDocumentLoader, requester);
    }

    @Test
    void refine_personalSkillLoadsReferencesAndCallsPipeline() throws Exception {
        WorkspaceMember member = mock(WorkspaceMember.class);
        SkillDraftRequest request = request("personal");
        var references = List.of(new SkillReferenceDocument("doc_1", "문서", "hash", "본문"));
        when(memberRepository.findByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(Optional.of(member));
        when(referenceDocumentLoader.load("ws_1", request.referenceDocumentIds())).thenReturn(references);
        when(requester.refine("ws_1", "user_1", request, references))
                .thenReturn(new ObjectMapper().readTree("{\"draft\":{\"command\":\"summary\"}}"));

        var result = service.refine("ws_1", "user_1", request);

        assertThat(result.path("draft").path("command").asText()).isEqualTo("summary");
        verify(requester).refine("ws_1", "user_1", request, references);
    }

    @Test
    void review_teamSkillRejectsMemberBeforeReferenceOrPipelineCall() {
        SkillDraftRequest request = request("team");
        WorkspaceMember member = member(WorkspaceRole.MEMBER);
        when(memberRepository.findByWorkspace_IdAndUser_Id("ws_1", "user_1"))
                .thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.review("ws_1", "user_1", request))
                .isInstanceOf(TeamSkillForbiddenException.class);

        verify(referenceDocumentLoader, never()).load("ws_1", request.referenceDocumentIds());
        verify(requester, never()).review("ws_1", "user_1", request, List.of());
    }

    private SkillDraftRequest request(String scope) {
        return new SkillDraftRequest(
                "summary", "요약", "문서를 요약한다.", scope, List.of("doc_1"), null, null, null);
    }

    private WorkspaceMember member(WorkspaceRole role) {
        WorkspaceMember member = mock(WorkspaceMember.class);
        when(member.getRole()).thenReturn(role);
        return member;
    }
}
