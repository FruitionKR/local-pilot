package fruition.core.aihistory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.dto.DocumentRestorePlan;
import fruition.core.aihistory.dto.PageRestorePlan;
import fruition.core.aihistory.dto.RestorePlan;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.wiki.domain.WikiPageContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestoreExecuteServiceTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String TARGET = "op_a2";
    private static final String TOKEN = "signed-token";
    private static final Instant T = Instant.parse("2026-07-28T10:00:00Z");

    @Mock RestorePreviewService previewService;
    @Mock RestoreScopeResolver scopeResolver;
    @Mock RestorePlanner planner;
    @Mock LintRestorePlanner lintRestorePlanner;
    @Mock PreviewTokenSigner tokenSigner;
    @Mock RestoreOperationLifecycle lifecycle;
    @Mock DocumentRestorePlanner documentPlanner;
    @Mock DocumentRestoreApplier documentApplier;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock RestoreTargetValidator validator;

    private RestoreExecuteService service;

    @BeforeEach
    void setUp() {
        service = new RestoreExecuteService(previewService, scopeResolver, planner,
                lintRestorePlanner, tokenSigner, lifecycle, documentPlanner, documentApplier,
                outboxWriter, validator, new ObjectMapper(), "ai.maintenance.command");
    }

    @Test
    void wikiRestore_storesApprovedManifestAndQueuesCommand() {
        PageRestorePlan page = PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1);
        WikiPageContribution contribution =
                new WikiPageContribution("wp_S_A", "op_a1", "doc_A", 1, "wiki/a.md", T);
        OperationLog target = target(OperationType.ingest);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2")))
                .thenReturn(Map.of("wp_S_A", List.of(contribution)));
        RestorePlan plan = new RestorePlan(List.of(page));
        when(planner.plan(any(), any())).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, Map.of("wp_S_A", List.of(contribution))))
                .thenReturn(true);
        when(validator.requireApplicable(target, plan)).thenReturn(page);
        when(lifecycle.startQueued(eq(target), anyString(), any())).thenReturn(restore);

        var response = service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.operationId()).isEqualTo("op_restore");
        ArgumentCaptor<Object> command = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(eq(response.runId()), eq("ai.maintenance.command"),
                eq(WORKSPACE), command.capture());
        assertThat((RestoreExecuteService.RestoreCommand) command.getValue())
                .extracting(RestoreExecuteService.RestoreCommand::expectedContributions)
                .isEqualTo(Map.of("wp_S_A", List.of("op_a1:1:1")));
    }

    @Test
    void wikiRestore_rejectsStalePreviewBeforeCreatingRun() {
        OperationLog target = target(OperationType.ingest);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(scopeResolver.resolve(target)).thenReturn(Set.of("op_a2"));
        when(previewService.loadContributions(Set.of("op_a2"))).thenReturn(Map.of());
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of()));
        when(tokenSigner.matches(TOKEN, TARGET, Map.of())).thenReturn(false);

        assertThatThrownBy(() -> service.execute(WORKSPACE, USER, TARGET, TOKEN))
                .isInstanceOf(RestorePreviewStaleException.class);

        verify(lifecycle, never()).startQueued(any(), anyString(), any());
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void documentRestore_remainsSynchronous() {
        OperationLog target = target(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        OperationLog restore = OperationLog.applying(
                "op_restore", WORKSPACE, USER, "doc_A", TARGET, "{}", T);
        when(previewService.loadOperation(WORKSPACE, USER, TARGET)).thenReturn(target);
        when(documentPlanner.plan(target)).thenReturn(plan);
        when(tokenSigner.matches(TOKEN, TARGET, plan)).thenReturn(true);
        when(lifecycle.start(eq(target), anyString(), any())).thenReturn(restore);
        when(documentApplier.apply(restore, plan)).thenReturn(7L);

        var response = service.execute(WORKSPACE, USER, TARGET, TOKEN);

        assertThat(response.status()).isEqualTo("succeeded");
        verify(lifecycle).finishDocument(eq("op_restore"), eq(5L), eq(7L), any());
        verify(outboxWriter, never()).enqueue(any(), any(), any(), any());
    }

    private OperationLog target(OperationType type) {
        return OperationLog.completed(TARGET, WORKSPACE, USER, type,
                "doc_A", "요약", 1, T);
    }
}
