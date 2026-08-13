package fruition.core.document.mongo;

import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.shared.idempotency.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = MongoDocumentEditStoreIntegrationTest.TestApplication.class)
class MongoDocumentEditStoreIntegrationTest {

    static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7.0");
    static boolean containerStartedByTest;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MongoDocumentEditStoreIntegrationTest::mongoUri);
    }

    static String mongoUri() {
        String externalUri = System.getenv("DOCUMENT_MONGODB_TEST_URI");
        if (externalUri != null && !externalUri.isBlank()) {
            return externalUri;
        }
        MONGODB.start();
        containerStartedByTest = true;
        return MONGODB.getReplicaSetUrl("document_test");
    }

    @AfterAll
    static void stopContainer() {
        if (containerStartedByTest) {
            MONGODB.stop();
        }
    }

    @Autowired MongoTemplate mongoTemplate;
    @Autowired MongoDocumentEditStore store;
    @Autowired @Qualifier("mongoTransactionManager") PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        mongoTemplate.getDb().drop();
    }

    @Test
    void save_commitsStateWriteAndOutboxInOneTransaction() {
        DocumentEditState legacy = legacyState();

        MongoDocumentEditSaveResult result = store.save(
                "ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy);

        MongoDocumentEditState state = mongoTemplate.findById("doc_1", MongoDocumentEditState.class);
        assertThat(state).isNotNull();
        assertThat(state.getRevision()).isEqualTo(2);
        assertThat(state.getMarkdown()).isEqualTo("# 변경\n");
        assertThat(result.baseMarkdown()).isEqualTo("# 기존\n");
        assertThat(result.baseContentHash()).isEqualTo("old-hash");
        assertThat(result.revision()).isEqualTo(2);
        assertThat(mongoTemplate.count(new Query(), MongoDocumentEditWrite.class)).isEqualTo(1);
        assertThat(mongoTemplate.count(new Query(), MongoDocumentEditOutboxEvent.class)).isEqualTo(1);
    }

    @Test
    void save_noOpLosesCasWhenConcurrentContentChangeCommitsFirst() throws Exception {
        DocumentEditState legacy = legacyState();
        mongoTemplate.insert(new MongoDocumentEditState(
                "doc_1", "ws_1", "# 기존\n", 1, "old-hash", "user_1", Instant.now()));
        CountDownLatch bothReadBaseRevision = new CountDownLatch(2);
        CountDownLatch changeCommitted = new CountDownLatch(1);
        MongoDocumentEditStore racingStore = new MongoDocumentEditStore(
                mongoTemplate,
                transactionManager,
                changed -> {
                    bothReadBaseRevision.countDown();
                    await(bothReadBaseRevision);
                    if (!changed) {
                        await(changeCommitted);
                    }
                }
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> changed = executor.submit(() -> {
                try {
                    racingStore.save("ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                            "write_changed", "user_1", 1, legacy);
                    return true;
                } finally {
                    changeCommitted.countDown();
                }
            });
            Future<Boolean> noOp = executor.submit(() -> {
                try {
                    racingStore.save("ws_1", "doc_1", "# 기존\n", "old-hash", 1,
                            "write_noop", "user_1", 1, legacy);
                    return true;
                } catch (DocumentVersionConflictException exception) {
                    return false;
                }
            });

            assertThat(changed.get()).isTrue();
            assertThat(noOp.get()).isFalse();
            assertThat(mongoTemplate.findById(
                    MongoDocumentEditWrite.id("doc_1", "write_noop"), MongoDocumentEditWrite.class))
                    .isNull();
            MongoDocumentEditState state = mongoTemplate.findById("doc_1", MongoDocumentEditState.class);
            assertThat(state.getRevision()).isEqualTo(2);
            assertThat(state.getLastWriteId()).isEqualTo("write_changed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void save_replaysSameRevisionWriteIdAndRejectsDifferentRequest() {
        DocumentEditState legacy = legacyState();
        MongoDocumentEditSaveResult first = store.save(
                "ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy);

        MongoDocumentEditSaveResult replay = store.save(
                "ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy);

        assertThat(replay).isEqualTo(first);
        assertThat(mongoTemplate.count(new Query(), MongoDocumentEditWrite.class)).isEqualTo(1);
        assertThat(mongoTemplate.count(new Query(), MongoDocumentEditOutboxEvent.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.save(
                "ws_1", "doc_1", "다른 본문", "other-hash", 1,
                "write_1", "user_1", 1, legacy))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void save_rejectsAgentReplayOfManualReceipt() {
        DocumentEditState legacy = legacyState();
        store.save("ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy);

        assertThatThrownBy(() -> store.save(
                "ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy, "op_agent_1"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(mongoTemplate.count(new Query(), MongoDocumentEditOutboxEvent.class)).isEqualTo(1);
    }

    @Test
    void save_rejectsStaleBaseRevision() {
        DocumentEditState legacy = legacyState();
        store.save("ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy);

        assertThatThrownBy(() -> store.save(
                "ws_1", "doc_1", "# 다음\n", "next-hash", 1,
                "write_2", "user_1", 1, legacy))
                .isInstanceOf(DocumentVersionConflictException.class);
    }

    @Test
    void save_concurrentCasAllowsExactlyOneWrite() throws Exception {
        DocumentEditState legacy = legacyState();
        mongoTemplate.insert(new MongoDocumentEditState(
                "doc_1", "ws_1", "# 기존\n", 1, "old-hash", "user_1", Instant.now()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> saveAfterStart(
                    start, legacy, "# 첫 번째\n", "first-hash", "write_1"));
            Future<Boolean> second = executor.submit(() -> saveAfterStart(
                    start, legacy, "# 두 번째\n", "second-hash", "write_2"));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(mongoTemplate.count(new Query(), MongoDocumentEditWrite.class)).isEqualTo(1);
            assertThat(mongoTemplate.count(new Query(), MongoDocumentEditOutboxEvent.class)).isEqualTo(1);
            assertThat(mongoTemplate.findById("doc_1", MongoDocumentEditState.class).getRevision())
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void save_rollsBackStateAndWriteWhenOutboxInsertFails() {
        DocumentEditState legacy = legacyState();
        mongoTemplate.insert(new MongoDocumentEditOutboxEvent(
                MongoDocumentEditWrite.id("doc_1", "write_1"),
                "occupied", "ws_1", 1, "occupied-hash", Instant.now()));

        assertThatThrownBy(() -> store.save(
                "ws_1", "doc_1", "# 변경\n", "new-hash", 1,
                "write_1", "user_1", 1, legacy))
                .isInstanceOf(RuntimeException.class);

        assertThat(mongoTemplate.findById("doc_1", MongoDocumentEditState.class)).isNull();
        assertThat(mongoTemplate.findById(
                MongoDocumentEditWrite.id("doc_1", "write_1"), MongoDocumentEditWrite.class)).isNull();
    }

    private DocumentEditState legacyState() {
        return new DocumentEditState("doc_1", "# 기존\n", "old-hash");
    }

    private boolean saveAfterStart(
            CountDownLatch start,
            DocumentEditState legacy,
            String markdown,
            String contentHash,
            String revisionWriteId
    ) throws InterruptedException {
        start.await();
        try {
            store.save("ws_1", "doc_1", markdown, contentHash, 1,
                    revisionWriteId, "user_1", 1, legacy);
            return true;
        } catch (DocumentVersionConflictException exception) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기가 중단되었습니다.", exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import({MongoDocumentEditStore.class, MongoTransactionConfig.class})
    static class TestApplication {}
}
