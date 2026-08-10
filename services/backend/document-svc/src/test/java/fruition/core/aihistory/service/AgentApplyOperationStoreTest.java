package fruition.core.aihistory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentApplyOperationStoreTest {

    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void consume_acceptsOnlyOneCompletedRun() {
        AgentApplyOperationStore store = new AgentApplyOperationStore(jdbcTemplate);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("op_1"), org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("doc_1"))).thenReturn(1, 0);

        assertThat(store.consume("op_1", "user_1", "doc_1")).isTrue();
        assertThat(store.consume("op_1", "user_1", "doc_1")).isFalse();
        verify(jdbcTemplate, times(2)).update(org.mockito.ArgumentMatchers.contains("agent_apply_projections"),
                org.mockito.ArgumentMatchers.eq("op_1"), org.mockito.ArgumentMatchers.eq("user_1"),
                org.mockito.ArgumentMatchers.eq("doc_1"));
    }

    @Test
    void newOperationId_isUnique() {
        AgentApplyOperationStore store = new AgentApplyOperationStore(jdbcTemplate);
        assertThat(store.newOperationId()).isNotEqualTo(store.newOperationId());
    }
}
