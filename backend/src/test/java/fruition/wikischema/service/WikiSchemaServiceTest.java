package fruition.wikischema.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.wikischema.dto.WikiSchemaDraftRequest;
import fruition.wikischema.dto.WikiSchemaPreviewRequest;
import fruition.wikischema.repository.PipelineWikiSchemaRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiSchemaServiceTest {

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiSchemaRequester requester;

    private WikiSchemaService service;

    @BeforeEach
    void setUp() {
        service = new WikiSchemaService(workspaceMemberRepository, requester);
    }

    @Test
    void preview_delegatesToRequesterForMember() throws Exception {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(requester.preview("# 원문"))
                .thenReturn(new ObjectMapper().readTree("{\"preview_markdown\":\"# 미리보기\"}"));

        var result = service.preview("ws_1", "user_1", new WikiSchemaPreviewRequest("# 원문"));

        assertThat(result.path("preview_markdown").asText()).isEqualTo("# 미리보기");
    }

    @Test
    void createDraft_forwardsPathWorkspaceAndPrincipalUser() throws Exception {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(requester.createDraft("# 원문", "기본", "ws_1", "user_1"))
                .thenReturn(new ObjectMapper().readTree("{\"wiki_schema\":{\"id\":\"sch_1\"}}"));

        var result = service.createDraft("ws_1", "user_1", new WikiSchemaDraftRequest("# 원문", "기본"));

        assertThat(result.path("wiki_schema").path("id").asText()).isEqualTo("sch_1");
        verify(requester).createDraft("# 원문", "기본", "ws_1", "user_1");
    }

    @Test
    void preview_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_2")).thenReturn(false);

        assertThatThrownBy(() -> service.preview("ws_1", "user_2", new WikiSchemaPreviewRequest("# 원문")))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
    }

    @Test
    void activate_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_2")).thenReturn(false);

        assertThatThrownBy(() -> service.activate("ws_1", "user_2", "sch_1"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(requester, never()).activate("sch_1");
    }
}
