package fruition.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.skill.dto.SkillDraftRequest;
import fruition.skill.repository.PipelineSkillRequester;
import fruition.skill.repository.SkillRepository;
import fruition.skill.repository.SkillVersionRepository;
import fruition.document.repository.DocumentRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    @Mock WorkspaceMemberRepository memberRepository;
    @Mock SkillReferenceDocumentLoader referenceDocumentLoader;
    @Mock PipelineSkillRequester requester;
    @Mock SkillRepository skillRepository;
    @Mock SkillVersionRepository versionRepository;
    @Mock DocumentRepository documentRepository;
    @Mock SkillReviewTokenSigner tokenSigner;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(memberRepository, referenceDocumentLoader, requester,
                skillRepository, versionRepository, documentRepository, tokenSigner, new ObjectMapper());
    }

    @Test
    void refine_personalSkillLoadsReferencesAndCallsPipeline() throws Exception {
        SkillDraftRequest request = request("personal");
        var references = List.of(new SkillReferenceDocument("doc_1", "문서", "hash", "본문"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(referenceDocumentLoader.load("ws_1", request.referenceDocumentIds())).thenReturn(references);
        when(requester.refine("ws_1", "user_1", request, references))
                .thenReturn(new ObjectMapper().readTree("{\"draft\":{\"command\":\"summary\"}}"));

        var result = service.refine("ws_1", "user_1", request);

        assertThat(result.path("draft").path("command").asText()).isEqualTo("summary");
        verify(requester).refine("ws_1", "user_1", request, references);
    }

    @Test
    void review_teamSkillAllowsMember() throws Exception {
        SkillDraftRequest request = request("team");
        var references = List.of(new SkillReferenceDocument("doc_1", "문서", "hash", "본문"));
        when(memberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(referenceDocumentLoader.load("ws_1", request.referenceDocumentIds())).thenReturn(references);
        when(requester.review("ws_1", "user_1", request, references))
                .thenReturn(new ObjectMapper().readTree("{\"has_blocked_issues\":false}"));
        when(tokenSigner.issue(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("review-token");

        var result = service.review("ws_1", "user_1", request);

        assertThat(result.path("publish_allowed").asBoolean()).isTrue();
        assertThat(result.path("review_token").asText()).isEqualTo("review-token");
    }

    private SkillDraftRequest request(String scope) {
        return new SkillDraftRequest(
                "summary", "요약", "문서를 요약한다.", scope, List.of("doc_1"), null, null, null);
    }

}
