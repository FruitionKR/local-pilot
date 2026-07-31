package fruition.aihistory.service;

import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.repository.OperationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 복구 범위 결정. 사용자가 고르지 않고 기준 작업 하나로 정해진다.
 */
@ExtendWith(MockitoExtension.class)
class RestoreScopeResolverTest {

    private static final String WORKSPACE = "ws_1";
    private static final String USER = "user_1";
    private static final Instant T2 = Instant.parse("2026-07-28T10:00:00Z");

    @Mock OperationLogRepository operationLogRepository;

    private RestoreScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RestoreScopeResolver(operationLogRepository);
    }

    @Test
    @DisplayName("기준 작업 이후 같은 문서의 ingest를 전부 제외한다")
    void collectsLaterOperationsOfSameDocument() {
        OperationLog target = ingest("op_a2", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter("doc_A", T2, OperationType.ingest))
                .thenReturn(List.of(
                        ingest("op_a3", "doc_A", T2.plusSeconds(3600)),
                        ingest("op_a4", "doc_A", T2.plusSeconds(7200))));

        assertThat(resolver.resolve(target)).containsExactly("op_a3", "op_a4");
    }

    @Test
    @DisplayName("기준 작업 자신은 살린다")
    void keepsTargetItself() {
        OperationLog target = ingest("op_a2", "doc_A", T2);
        // 같은 시각의 작업이 조회에 섞여 들어와도 기준 작업은 빠져야 한다.
        when(operationLogRepository.findByTargetDocumentAfter(any(), any(), any()))
                .thenReturn(List.of(target, ingest("op_a3", "doc_A", T2.plusSeconds(3600))));

        assertThat(resolver.resolve(target)).containsExactly("op_a3");
    }

    @Test
    @DisplayName("이후 작업이 없으면 제외 집합이 비어 아무 페이지도 건드리지 않는다")
    void emptyWhenNothingFollows() {
        OperationLog target = ingest("op_a4", "doc_A", T2);
        when(operationLogRepository.findByTargetDocumentAfter(any(), any(), any()))
                .thenReturn(List.of(target));

        assertThat(resolver.resolve(target)).isEmpty();
    }

    @Test
    @DisplayName("lint는 원문 문서가 없어 그 작업 하나만 취소한다")
    void lintCancelsItselfOnly() {
        OperationLog lint = OperationLog.processing(
                "op_lint", WORKSPACE, USER, OperationType.lint, null, T2);

        assertThat(resolver.resolve(lint)).isEqualTo(Set.of("op_lint"));
        // 문서 범위 조회를 시도하지 않는다.
        verify(operationLogRepository, never())
                .findByTargetDocumentAfter(any(), any(), eq(OperationType.ingest));
    }

    private OperationLog ingest(String id, String documentId, Instant at) {
        return OperationLog.processing(id, WORKSPACE, USER, OperationType.ingest, documentId, at);
    }
}
