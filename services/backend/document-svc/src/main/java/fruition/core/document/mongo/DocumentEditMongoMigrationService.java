package fruition.core.document.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Component
public class DocumentEditMongoMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditMongoMigrationService.class);
    private static final String LEGACY_ROWS_SQL = """
            SELECT s.document_id, d.workspace_id, s.markdown, d.current_version,
                   s.content_hash, d.user_id, s.updated_at
            FROM document_edit_states s
            JOIN documents d ON d.id = s.document_id
            ORDER BY s.document_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final String migrationMode;

    public DocumentEditMongoMigrationService(
            JdbcTemplate jdbcTemplate,
            MongoTemplate mongoTemplate,
            @Value("${app.document-edit.mongo.migration-mode:none}") String migrationMode
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.mongoTemplate = mongoTemplate;
        this.migrationMode = migrationMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (migrationMode) {
            case "none" -> { }
            case "backfill" -> backfill();
            case "validate" -> validate();
            case "backfill-validate" -> {
                backfill();
                validate();
            }
            default -> throw new IllegalStateException(
                    "지원하지 않는 DOCUMENT_EDIT_MONGO_MIGRATION_MODE입니다: " + migrationMode);
        }
    }

    public BackfillResult backfill() {
        List<LegacyEditState> legacyStates = legacyStates();
        int inserted = 0;
        for (LegacyEditState legacy : legacyStates) {
            Query query = Query.query(Criteria.where("_id").is(legacy.documentId()));
            if (mongoTemplate.exists(query, MongoDocumentEditState.class)) {
                continue;
            }
            mongoTemplate.insert(legacy.toMongoState());
            inserted++;
        }
        BackfillResult result = new BackfillResult(legacyStates.size(), inserted);
        log.info("[Document MongoDB backfill] source={} inserted={}", result.sourceCount(), result.insertedCount());
        return result;
    }

    public ValidationResult validate() {
        List<LegacyEditState> legacyStates = legacyStates();
        int missing = 0;
        int mismatched = 0;
        for (LegacyEditState legacy : legacyStates) {
            MongoDocumentEditState state = mongoTemplate.findById(
                    legacy.documentId(), MongoDocumentEditState.class);
            if (state == null) {
                missing++;
            } else if (!legacy.matches(state)) {
                mismatched++;
            }
        }
        ValidationResult result = new ValidationResult(legacyStates.size(), missing, mismatched);
        if (!result.valid()) {
            throw new IllegalStateException(
                    "Document MongoDB 검증 실패: source=" + result.sourceCount()
                            + ", missing=" + result.missingCount()
                            + ", mismatched=" + result.mismatchedCount());
        }
        log.info("[Document MongoDB validation] source={} missing=0 mismatched=0", result.sourceCount());
        return result;
    }

    private List<LegacyEditState> legacyStates() {
        return jdbcTemplate.query(LEGACY_ROWS_SQL, this::mapLegacyState);
    }

    private LegacyEditState mapLegacyState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LegacyEditState(
                resultSet.getString("document_id"),
                resultSet.getString("workspace_id"),
                resultSet.getString("markdown"),
                resultSet.getLong("current_version"),
                resultSet.getString("content_hash"),
                resultSet.getString("user_id"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    record LegacyEditState(
            String documentId,
            String workspaceId,
            String markdown,
            long revision,
            String contentHash,
            String updatedBy,
            Instant updatedAt
    ) {
        MongoDocumentEditState toMongoState() {
            return new MongoDocumentEditState(
                    documentId, workspaceId, markdown, revision, contentHash, updatedBy, updatedAt);
        }

        boolean matches(MongoDocumentEditState state) {
            return workspaceId.equals(state.getWorkspaceId())
                    && markdown.equals(state.getMarkdown())
                    && revision == state.getRevision()
                    && contentHash.equals(state.getContentHash());
        }
    }

    public record BackfillResult(int sourceCount, int insertedCount) {}

    public record ValidationResult(int sourceCount, int missingCount, int mismatchedCount) {
        public boolean valid() {
            return missingCount == 0 && mismatchedCount == 0;
        }
    }
}
