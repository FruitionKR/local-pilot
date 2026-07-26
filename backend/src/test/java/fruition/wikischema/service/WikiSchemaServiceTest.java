package fruition.wikischema.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.wikischema.dto.CreateWikiSchemaDraftRequest;
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
    @Mock PipelineWikiSchemaRequester pipelineWikiSchemaRequester;

    private WikiSchemaService service;

    @BeforeEach
    void setUp() {
        service = new WikiSchemaService(workspaceMemberRepository, pipelineWikiSchemaRequester);
    }

    @Test
    void preview_passesThroughPipelineResponseForMember() throws Exception {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(pipelineWikiSchemaRequester.preview("# 제목"))
                .thenReturn(new ObjectMapper().readTree("{\"has_blocked_issues\":true}"));

        var response = service.preview("ws_1", "user_1", "# 제목");

        assertThat(response.path("has_blocked_issues").asBoolean()).isTrue();
    }

    @Test
    void preview_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(false);

        assertThatThrownBy(() -> service.preview("ws_1", "user_1", "# 제목"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(pipelineWikiSchemaRequester);
    }

    @Test
    void createDraft_blankNameFallsBackToPipelineDefault() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        service.createDraft("ws_1", "user_1", new CreateWikiSchemaDraftRequest("# 제목", "  "));

        verify(pipelineWikiSchemaRequester).createDraft("# 제목", "ws_1", "user_1", null);
    }

    @Test
    void createDraft_keepsProvidedName() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        service.createDraft("ws_1", "user_1", new CreateWikiSchemaDraftRequest("# 제목", "규칙집"));

        verify(pipelineWikiSchemaRequester).createDraft("# 제목", "ws_1", "user_1", "규칙집");
    }

    @Test
    void activate_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(false);

        assertThatThrownBy(() -> service.activate("ws_1", "user_1", "schema_9"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(pipelineWikiSchemaRequester, never()).activate("schema_9");
    }
}
