package fruition.core.wikimaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.repository.PipelineRunStatusRequester;
import fruition.core.wiki.repository.PipelineWikiStateRequester;
import fruition.core.wikimaintenance.dto.WikiLintRequest;
import fruition.core.wikimaintenance.repository.WikiLintStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMaintenanceServiceTest {

    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock LintOperationStarter operationStarter;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock PipelineRunStatusRequester runStatusRequester;
    @Mock WikiLintStateRepository lintStateRepository;
    @Mock PipelineWikiStateRequester wikiStateRequester;

    private WikiMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new WikiMaintenanceService(workspaceAccessGuard, operationStarter, outboxWriter,
                runStatusRequester, lintStateRepository, wikiStateRequester, new ObjectMapper(),
                "ai.maintenance.command");
    }

    @Test
    void lint_dryRunQueuesWithoutOperation() {
        var result = service.lint("ws_1", "user_1", new WikiLintRequest(true, true));

        assertThat(result.path("status").asText()).isEqualTo("queued");
        verify(operationStarter, never()).start(any(), any());
        verify(outboxWriter).enqueue(any(), org.mockito.ArgumentMatchers.eq("ai.maintenance.command"),
                org.mockito.ArgumentMatchers.eq("ws_1"), any());
    }

    @Test
    void lint_writeStartsOperationAndQueuesCommand() {
        when(operationStarter.start("ws_1", "user_1")).thenReturn("op_lint_1");

        var result = service.lint("ws_1", "user_1", new WikiLintRequest(false, false));

        assertThat(result.path("operation_id").asText()).isEqualTo("op_lint_1");
        verify(outboxWriter).enqueue(any(), org.mockito.ArgumentMatchers.eq("ai.maintenance.command"),
                org.mockito.ArgumentMatchers.eq("ws_1"), any());
    }

    @Test
    void lint_rejectsNonMemberBeforeQueue() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_2");

        assertThatThrownBy(() -> service.lint("ws_1", "user_2", new WikiLintRequest(null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }
}
