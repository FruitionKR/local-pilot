package fruition.core.document.mongo;

import com.mongodb.MongoException;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.shared.idempotency.IdempotencyConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class MongoDocumentEditStore {

    private final MongoTemplate mongoTemplate;
    private final TransactionTemplate transactionTemplate;
    private final BeforeStateWrite beforeStateWrite;

    @Autowired
    public MongoDocumentEditStore(
            MongoTemplate mongoTemplate,
            @Qualifier("mongoTransactionManager") PlatformTransactionManager transactionManager
    ) {
        this(mongoTemplate, transactionManager, changed -> { });
    }

    MongoDocumentEditStore(
            MongoTemplate mongoTemplate,
            PlatformTransactionManager transactionManager,
            BeforeStateWrite beforeStateWrite
    ) {
        this.mongoTemplate = mongoTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.beforeStateWrite = beforeStateWrite;
    }

    public Optional<MongoDocumentEditState> findState(String documentId) {
        return Optional.ofNullable(mongoTemplate.findById(documentId, MongoDocumentEditState.class));
    }

    public MongoDocumentEditSaveResult save(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            long initialRevision,
            DocumentEditState legacyState
    ) {
        RuntimeException lastTransientError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate.execute(status -> saveInTransaction(
                        workspaceId,
                        documentId,
                        markdown,
                        contentHash,
                        baseRevision,
                        revisionWriteId,
                        actorUserId,
                        initialRevision,
                        legacyState
                ));
            } catch (RuntimeException exception) {
                if (!isTransientTransactionError(exception)) {
                    throw exception;
                }
                lastTransientError = exception;
                if (attempt < 2) {
                    waitBeforeRetry(attempt);
                }
            }
        }
        if (isWriteConflict(lastTransientError)) {
            throw versionConflict();
        }
        throw new IllegalStateException(
                "MongoDB 문서 편집 transaction 재시도에 실패했습니다.", lastTransientError);
    }

    private MongoDocumentEditSaveResult saveInTransaction(
            String workspaceId,
            String documentId,
            String markdown,
            String contentHash,
            long baseRevision,
            String revisionWriteId,
            String actorUserId,
            long initialRevision,
            DocumentEditState legacyState
    ) {
        String requestHash = requestHash(baseRevision, contentHash);
        MongoDocumentEditWrite existing = mongoTemplate.findById(
                MongoDocumentEditWrite.id(documentId, revisionWriteId),
                MongoDocumentEditWrite.class
        );
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "같은 revision_write_id를 다른 저장 요청에 사용할 수 없습니다.");
            }
            return new MongoDocumentEditSaveResult(
                    existing.getBaseRevision(),
                    existing.getBaseMarkdown(),
                    existing.getBaseContentHash(),
                    existing.getResultRevision(),
                    existing.getRequestContentHash(),
                    existing.getResultUpdatedAt(),
                    existing.getActorUserId(),
                    existing.isChanged()
            );
        }

        ensureState(workspaceId, documentId, initialRevision, actorUserId, legacyState);
        MongoDocumentEditState current = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(documentId)
                        .and("workspaceId").is(workspaceId)
                        .and("revision").is(baseRevision)),
                MongoDocumentEditState.class
        );
        if (current == null) {
            throw versionConflict();
        }

        String baseMarkdown = current.getMarkdown();
        String baseContentHash = current.getContentHash();
        Instant writeAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant updatedAt = writeAt;
        boolean changed = !current.getContentHash().equals(contentHash);
        long resultRevision = changed ? baseRevision + 1 : baseRevision;
        beforeStateWrite.run(changed);
        if (changed) {
            current = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(documentId)
                            .and("workspaceId").is(workspaceId)
                            .and("revision").is(baseRevision)),
                    new Update()
                            .set("markdown", markdown)
                            .set("contentHash", contentHash)
                            .set("updatedBy", actorUserId)
                            .set("updatedAt", updatedAt)
                            .set("lastWriteId", revisionWriteId)
                            .set("lastWriteAt", writeAt)
                            .inc("revision", 1),
                    FindAndModifyOptions.options().returnNew(true),
                    MongoDocumentEditState.class
            );
            if (current == null) {
                throw versionConflict();
            }
        } else {
            current = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(documentId)
                            .and("workspaceId").is(workspaceId)
                            .and("revision").is(baseRevision)),
                    new Update()
                            .set("lastWriteId", revisionWriteId)
                            .set("lastWriteAt", writeAt),
                    FindAndModifyOptions.options().returnNew(true),
                    MongoDocumentEditState.class
            );
            if (current == null) {
                throw versionConflict();
            }
            updatedAt = current.getUpdatedAt();
        }

        mongoTemplate.insert(new MongoDocumentEditWrite(
                documentId,
                revisionWriteId,
                baseRevision,
                baseMarkdown,
                baseContentHash,
                resultRevision,
                contentHash,
                requestHash,
                actorUserId,
                changed,
                updatedAt,
                writeAt
        ));
        if (changed) {
            mongoTemplate.insert(new MongoDocumentEditOutboxEvent(
                    MongoDocumentEditWrite.id(documentId, revisionWriteId),
                    documentId,
                    workspaceId,
                    resultRevision,
                    contentHash,
                    updatedAt
            ));
        }
        return new MongoDocumentEditSaveResult(
                baseRevision,
                baseMarkdown,
                baseContentHash,
                resultRevision,
                contentHash,
                updatedAt,
                actorUserId,
                changed
        );
    }

    private boolean isTransientTransactionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && (mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || mongoException.getCode() == 112)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isWriteConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MongoException mongoException && mongoException.getCode() == 112) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(10L * (attempt + 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "MongoDB 문서 편집 transaction 재시도 대기가 중단되었습니다.", exception);
        }
    }

    private void ensureState(
            String workspaceId,
            String documentId,
            long initialRevision,
            String actorUserId,
            DocumentEditState legacyState
    ) {
        if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(documentId)),
                MongoDocumentEditState.class)) {
            return;
        }
        mongoTemplate.insert(new MongoDocumentEditState(
                documentId,
                workspaceId,
                legacyState.getMarkdown(),
                initialRevision,
                legacyState.getContentHash(),
                actorUserId,
                legacyState.getUpdatedAt()
        ));
    }

    private String requestHash(long baseRevision, String contentHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (baseRevision + "\0" + contentHash).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("저장 요청 hash를 계산할 수 없습니다.", exception);
        }
    }

    private DocumentVersionConflictException versionConflict() {
        return new DocumentVersionConflictException(
                "다른 변경이 먼저 저장되었습니다. 최신 문서를 다시 조회해 주세요.");
    }

    @FunctionalInterface
    interface BeforeStateWrite {
        void run(boolean changed);
    }
}
