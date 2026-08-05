package fruition.wikimaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.aihistory.service.LintOperationStarter;
import fruition.aihistory.service.OperationIngestService;
import fruition.wikimaintenance.dto.WikiLintRequest;
import fruition.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.wikimaintenance.repository.PipelineWikiLintResponse;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock PipelineWikiMaintenanceRequester requester;
    @Mock LintOperationStarter operationStarter;
    @Mock OperationIngestService operationIngestService;

    private WikiMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WikiMaintenanceService(
                workspaceMemberRepository, requester, operationStarter, operationIngestService);
    }

    @Test
    void lint_delegatesToRequesterForMember() throws Exception {
        WikiLintRequest request = new WikiLintRequest(false, true);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        var body = new ObjectMapper().readTree("{\"cluster_count\":2}");
        when(requester.lint("ws_1", "user_1", request, null))
                .thenReturn(new PipelineWikiLintResponse(null, java.util.List.of(), body));

        var result = service.lint("ws_1", "user_1", request);

        assertThat(result.path("cluster_count").asInt()).isEqualTo(2);
        verifyNoInteractions(operationStarter);
    }

    @Test
    void lint_startsOperationAndPassesIdForExecute() throws Exception {
        WikiLintRequest request = new WikiLintRequest(true, false);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");
        var body = new ObjectMapper().readTree("{\"cluster_count\":2}");
        when(requester.lint("ws_1", "user_1", request, "op_lint_1"))
                .thenReturn(new PipelineWikiLintResponse("op_lint_1", java.util.List.of(), body));

        service.lint("ws_1", "user_1", request);

        verify(operationStarter).start("ws_1", "user_1");
        verify(requester).lint("ws_1", "user_1", request, "op_lint_1");
        verify(operationIngestService).accept(org.mockito.ArgumentMatchers.eq("op_lint_1"), any());
    }

    @Test
    void lint_marksOperationFailedWhenExecuteRequestFails() {
        WikiLintRequest request = new WikiLintRequest(true, false);
        var failure = new PipelineWikiMaintenanceException("pipeline failure", 503, null);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_1")).thenReturn(true);
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");
        when(requester.lint("ws_1", "user_1", request, "op_lint_1")).thenThrow(failure);

        assertThatThrownBy(() -> service.lint("ws_1", "user_1", request))
                .isSameAs(failure);

        verify(operationStarter).markFailed("op_lint_1", "pipeline failure");
    }

    @Test
    void lint_rejectsNonMemberBeforePipelineCall() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id("ws_1", "user_2")).thenReturn(false);

        assertThatThrownBy(() -> service.lint("ws_1", "user_2", new WikiLintRequest(null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
        verifyNoInteractions(operationStarter);
    }
}
