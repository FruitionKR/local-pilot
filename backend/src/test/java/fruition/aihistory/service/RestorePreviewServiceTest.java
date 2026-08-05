package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.dto.DocumentRestorePlan;
import fruition.aihistory.dto.PageRestorePlan;
import fruition.aihistory.dto.RestorePlan;
import fruition.aihistory.dto.RestorePreviewResponse;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.exception.OperationNotFoundException;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.wiki.repository.WikiPageContributionRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 복구 미리보기. 실행과 <b>같은 검증</b>을 거쳐야 한다.
 *
 * <p>여기서 통과한 것이 실행에서 거절되면 사용자가 확인 화면을 다 보고 나서 실패한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestorePreviewServiceTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final String OPERATION_ID = "op_a2";
    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Mock OperationLogRepository operationLogRepository;
    @Mock WikiPageContributionRepository contributionRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock RestoreScopeResolver scopeResolver;
    @Mock RestorePlanner planner;
    @Mock PreviewTokenSigner tokenSigner;
    @Mock DocumentRestorePlanner documentPlanner;
    @Mock RestoreTargetValidator validator;

    private RestorePreviewService service;

    @BeforeEach
    void setUp() {
        service = new RestorePreviewService(operationLogRepository, contributionRepository,
                workspaceMemberRepository, scopeResolver, planner, tokenSigner,
                documentPlanner, validator);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE, USER))
                .thenReturn(true);
    }

    @Test
    @DisplayName("되돌릴 수 없는 유형은 계산하기 전에 거절한다")
    void rejectsNonRestorableType() {
        givenTarget(OperationType.restore);
        doThrow(new InvalidRestoreRequestException("되돌릴 수 없는 작업입니다."))
                .when(validator).requireRestorable(any());

        assertThatThrownBy(() -> service.preview(WORKSPACE, USER, OPERATION_ID))
                .isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    @DisplayName("실행이 거절할 계획은 미리보기도 거절한다")
    void rejectsPlanThatExecuteWouldReject() {
        givenTarget(OperationType.ingest);
        when(scopeResolver.resolve(any())).thenReturn(Set.of(OPERATION_ID));
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of()));
        when(validator.requireApplicable(any(), any()))
                .thenThrow(new InvalidRestoreRequestException("되돌릴 Wiki 페이지가 없습니다."));

        assertThatThrownBy(() -> service.preview(WORKSPACE, USER, OPERATION_ID))
                .isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    @DisplayName("정상이면 계획과 토큰을 함께 돌려준다")
    void returnsPlanWithToken() {
        givenTarget(OperationType.ingest);
        when(scopeResolver.resolve(any())).thenReturn(Set.of(OPERATION_ID));
        when(planner.plan(any(), any())).thenReturn(new RestorePlan(List.of(
                PageRestorePlan.delete("wp_C8"),
                PageRestorePlan.restore("wp_S_A", 2L, "op_a1", 1))));
        when(tokenSigner.sign(org.mockito.ArgumentMatchers.eq(OPERATION_ID),
                org.mockito.ArgumentMatchers.<java.util.Map<String,
                        java.util.List<fruition.wiki.domain.WikiPageContribution>>>any()))
                .thenReturn("token-abc");

        RestorePreviewResponse response = service.preview(WORKSPACE, USER, OPERATION_ID);

        assertThat(response.deleteCount()).isEqualTo(1);
        assertThat(response.restoreCount()).isEqualTo(1);
        assertThat(response.previewToken()).isEqualTo("token-abc");
        assertThat(response.document()).isNull();
    }

    @Test
    @DisplayName("문서 편집은 Wiki 계산을 타지 않는다")
    void documentEditSkipsWikiPath() {
        givenTarget(OperationType.document_edit);
        DocumentRestorePlan plan = new DocumentRestorePlan("doc_A", 6, 5);
        when(documentPlanner.plan(any())).thenReturn(plan);
        when(tokenSigner.sign(OPERATION_ID, plan)).thenReturn("token-abc");

        RestorePreviewResponse response = service.preview(WORKSPACE, USER, OPERATION_ID);

        assertThat(response.document().toVersion()).isEqualTo(5);
        assertThat(response.pages()).isEmpty();
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 404")
    void nonMemberIsRejected() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE, USER))
                .thenReturn(false);

        assertThatThrownBy(() -> service.preview(WORKSPACE, USER, OPERATION_ID))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    @DisplayName("없는 작업은 404")
    void unknownOperationIsNotFound() {
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(WORKSPACE, USER, OPERATION_ID))
                .isInstanceOf(OperationNotFoundException.class);
    }

    private void givenTarget(OperationType type) {
        when(operationLogRepository.findByOperationIdAndWorkspaceId(OPERATION_ID, WORKSPACE))
                .thenReturn(Optional.of(OperationLog.completed(OPERATION_ID, WORKSPACE, USER,
                        type, "doc_A", "요약", 1, NOW)));
    }
}
