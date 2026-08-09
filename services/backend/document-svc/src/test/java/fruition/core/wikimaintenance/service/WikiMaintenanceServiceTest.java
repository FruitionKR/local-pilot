package fruition.core.wikimaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.exception.PipelineWikiMaintenanceException;
import fruition.core.wikimaintenance.repository.PipelineWikiMaintenanceRequester;
import fruition.core.wikimaintenance.repository.PipelineWikiLintResponse;
import fruition.core.wikimaintenance.repository.WikiLintStateRepository;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.authz.WorkspaceAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock PipelineWikiMaintenanceRequester requester;
    @Mock LintOperationStarter operationStarter;
    @Mock OperationIngestService operationIngestService;
    @Mock WikiLintStateRepository lintStateRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;

    private WikiMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WikiMaintenanceService(
                workspaceAccessGuard,
                requester, operationStarter, operationIngestService,
                lintStateRepository, wikiStateRequester);
    }

    @Test
    void lint_delegatesToRequesterForMember() throws Exception {
        WikiLintRequest request = new WikiLintRequest(false, true);
        doNothing().when(workspaceAccessGuard).requireMember("ws_1", "user_1");
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
        doNothing().when(workspaceAccessGuard).requireMember("ws_1", "user_1");
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
        doNothing().when(workspaceAccessGuard).requireMember("ws_1", "user_1");
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");
        when(requester.lint("ws_1", "user_1", request, "op_lint_1")).thenThrow(failure);

        assertThatThrownBy(() -> service.lint("ws_1", "user_1", request))
                .isSameAs(failure);

        verify(operationStarter).markFailed("op_lint_1", "pipeline failure");
    }

    @Test
    void lint_rejectsNonMemberBeforePipelineCall() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.lint("ws_1", "user_2", new WikiLintRequest(null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(requester);
        verifyNoInteractions(operationStarter);
    }
}
