package fruition.core.document.mongo;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentEditMongoMigrationServiceTest {

    @Test
    void backfill_isIdempotentAndDoesNotOverwriteExistingMongoState() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplateWithLegacyState();
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.exists(any(), any(Class.class))).thenReturn(false, true);
        DocumentEditMongoMigrationService service =
                new DocumentEditMongoMigrationService(jdbcTemplate, mongoTemplate, "none");

        DocumentEditMongoMigrationService.BackfillResult result = service.backfill();
        DocumentEditMongoMigrationService.BackfillResult replay = service.backfill();

        assertThat(result.sourceCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(replay.sourceCount()).isEqualTo(1);
        assertThat(replay.insertedCount()).isZero();
        verify(mongoTemplate).insert(any(MongoDocumentEditState.class));
    }

    @Test
    void validate_failsWhenMongoStateDoesNotMatchPostgreSql() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplateWithLegacyState();
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.findById("doc_1", MongoDocumentEditState.class))
                .thenReturn(new MongoDocumentEditState(
                        "doc_1", "ws_1", "다른 본문", 1, "other-hash", "user_1", Instant.now()));
        DocumentEditMongoMigrationService service =
                new DocumentEditMongoMigrationService(jdbcTemplate, mongoTemplate, "none");

        assertThatThrownBy(service::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mismatched=1");
        verify(mongoTemplate, never()).insert(any());
    }

    @SuppressWarnings("unchecked")
    private JdbcTemplate jdbcTemplateWithLegacyState() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        Instant updatedAt = Instant.parse("2026-08-07T00:00:00Z");
        when(resultSet.getString("document_id")).thenReturn("doc_1");
        when(resultSet.getString("workspace_id")).thenReturn("ws_1");
        when(resultSet.getString("markdown")).thenReturn("# 기존\n");
        when(resultSet.getLong("current_version")).thenReturn(1L);
        when(resultSet.getString("content_hash")).thenReturn("old-hash");
        when(resultSet.getString("user_id")).thenReturn("user_1");
        when(resultSet.getTimestamp("updated_at"))
                .thenReturn(java.sql.Timestamp.from(updatedAt));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<DocumentEditMongoMigrationService.LegacyEditState> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        return jdbcTemplate;
    }
}
