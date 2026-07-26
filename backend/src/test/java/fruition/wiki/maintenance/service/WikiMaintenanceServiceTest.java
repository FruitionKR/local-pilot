package fruition.wiki.maintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.wiki.maintenance.dto.WikiMaintenanceLintRequest;
import fruition.wiki.maintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiMaintenanceRequester pipelineWikiMaintenanceRequester;

    private WikiMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WikiMaintenanceService(workspaceMemberRepository, pipelineWikiMaintenanceRequester);
    }

    @Test
    void lint_passesThroughForMember() throws Exception {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(pipelineWikiMaintenanceRequester.lint("ws_1", "user_1", false, true))
                .thenReturn(new ObjectMapper().readTree("{\"cluster_count\":5}"));

        var response = service.lint("ws_1", "user_1", new WikiMaintenanceLintRequest(false, true));

        assertThat(response.path("cluster_count").asInt()).isEqualTo(5);
    }

    @Test
    void lint_defaultsToDryRunWhenRequestOmitted() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        service.lint("ws_1", "user_1", null);

        verify(pipelineWikiMaintenanceRequester).lint("ws_1", "user_1", true, true);
    }

    @Test
    void lint_defaultsNullFlagsToTrue() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);

        service.lint("ws_1", "user_1", new WikiMaintenanceLintRequest(null, null));

        verify(pipelineWikiMaintenanceRequester).lint("ws_1", "user_1", true, true);
    }

    @Test
    void lint_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(false);

        assertThatThrownBy(() -> service.lint("ws_1", "user_1", new WikiMaintenanceLintRequest(true, true)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(pipelineWikiMaintenanceRequester);
    }
}
