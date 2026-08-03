package fruition.aihistory.repository;

import fruition.TestcontainersConfiguration;
import fruition.aihistory.dto.OperationResultRequest;
import fruition.aihistory.service.OperationApplier;
import fruition.wiki.repository.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 복구가 Wiki 페이지 행에 거는 잠금. 단위 테스트로는 확인할 수 없어 실제 Postgres로 검증한다.
 *
 * <p>확인하는 것은 둘이다. {@code findByIdForUpdate}가 정말로 행을 잠그는지, 그리고
 * {@link fruition.aihistory.service.RestoreApplier}가 {@code page_id} 오름차순으로 잠그는 것이
 * 교착을 막는지다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WikiPageLockIntegrationTest {

    private static final String LOCK_WAIT = "300ms";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PostgreSQLContainer<?> postgresContainer;
    @Autowired WikiPageRepository wikiPageRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired OperationApplier operationApplier;

    private String workspaceId;
    private String userId;
    private String pageA;
    private String pageB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        // page_id 오름차순이 a → b 가 되도록 접두어를 고정한다.
        pageA = "wp_a_" + suffix;
        pageB = "wp_b_" + suffix;

        jdbcTemplate.update("""
                INSERT INTO users(id, display_name, email, created_at, updated_at)
                VALUES (?, '테스터', ?, now(), now())
                """, userId, userId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO workspaces(id, name, created_at, updated_at)
                VALUES (?, '잠금 테스트', now(), now())
                """, workspaceId);
        insertPage(pageA, userId, "page-a");
        insertPage(pageB, userId, "page-b");
    }

    @Test
    @DisplayName("findByIdForUpdate가 행을 잠가 다른 트랜잭션이 기다린다")
    void holdsRowLockAgainstConcurrentWriter() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                wikiPageRepository.findByIdForUpdate(pageA).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            // 잠금이 풀리기를 기다리지 않고 짧은 대기 후 포기하게 해서, 실제로 막혔는지 확인한다.
            Future<Boolean> blocked = executor.submit(() -> !tryLock(pageA));
            assertThat(blocked.get(5, TimeUnit.SECONDS)).isTrue();

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);

            // 앞 트랜잭션이 끝나면 같은 행을 다시 잠글 수 있다.
            assertThat(tryLock(pageA)).isTrue();
        }
    }

    @Test
    @DisplayName("서로 다른 페이지는 동시에 잠글 수 있다")
    void doesNotBlockDifferentPages() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                wikiPageRepository.findByIdForUpdate(pageA).orElseThrow();
                locked.countDown();
                await(release);
                return null;
            }));

            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tryLock(pageB)).isTrue();

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("page_id 오름차순으로 잠그면 동시 복구에도 교착이 나지 않는다")
    void sortedOrderAvoidsDeadlock() throws Exception {
        // RestoreApplier와 같은 순서. 두 복구가 같은 두 페이지를 건드려도 순서가 같다.
        List<String> sorted = new ArrayList<>(List.of(pageA, pageB));
        sorted.sort(String::compareTo);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> lockAllInOrder(sorted, start));
            Future<?> second = executor.submit(() -> lockAllInOrder(sorted, start));
            start.countDown();

            // 교착이면 한쪽이 Postgres에 의해 중단되고 예외로 터진다.
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("반대 순서로 잠그면 교착이 난다 — 정렬이 필요한 이유")
    void reversedOrderDeadlocks() throws Exception {
        CountDownLatch bothLockedFirst = new CountDownLatch(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> forward = executor.submit(
                    () -> lockPairWithRawConnection(pageA, pageB, bothLockedFirst));
            Future<Boolean> backward = executor.submit(
                    () -> lockPairWithRawConnection(pageB, pageA, bothLockedFirst));

            boolean forwardOk = forward.get(20, TimeUnit.SECONDS);
            boolean backwardOk = backward.get(20, TimeUnit.SECONDS);

            // Postgres가 교착을 감지하면 한쪽만 중단시키고 다른 쪽은 커밋된다.
            // 둘 다 성공이면 교착이 재현되지 않은 것이고, 둘 다 실패면 다른 이유로 막힌 것이다.
            assertThat(forwardOk ^ backwardOk)
                    .as("정확히 한쪽만 교착으로 중단되어야 한다 (forward=%s, backward=%s)",
                            forwardOk, backwardOk)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("같은 페이지에 대한 ingest 콜백 2건이 동시에 와도 revision이 겹치지 않는다")
    void concurrentIngestCallbacksDoNotCollideOnRevision() throws Exception {
        String first = insertOperation();
        String second = insertOperation();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> applyIngest(first, "# 첫 번째 본문", start));
            Future<?> b = executor.submit(() -> applyIngest(second, "# 두 번째 본문", start));
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }

        List<Long> revisions = jdbcTemplate.queryForList(
                "SELECT revision FROM wiki_page_versions WHERE page_id = ? ORDER BY revision",
                Long.class, pageA);
        assertThat(revisions).as("두 콜백이 각각 다른 revision 을 받아야 한다")
                .containsExactly(1L, 2L);

        Long pointerMatchesLatest = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wiki_pages p"
                        + " JOIN wiki_page_versions v ON v.page_id = p.id"
                        + " WHERE p.id = ? AND v.revision = 2 AND p.markdown_uri = v.markdown_key",
                Long.class, pageA);
        assertThat(pointerMatchesLatest).as("markdown_uri 가 더 큰 revision 을 가리켜야 한다")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 작업이 다시 적재되면 기여도 버전도 늘지 않는다")
    void resendOfSameOperationIsSkipped() {
        String operationId = insertOperation();
        CountDownLatch open = new CountDownLatch(0);

        applyIngest(operationId, "# 본문", open);
        applyIngest(operationId, "# 본문", open);

        assertThat(count("SELECT count(*) FROM wiki_page_versions WHERE page_id = ?")).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM wiki_page_contributions WHERE page_id = ?")).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 작업이 같은 내용을 만들어도 기여는 따로 남는다")
    void identicalContentFromAnotherOperationStillRecordsContribution() {
        applyIngest(insertOperation(), "# 같은 본문", new CountDownLatch(0));
        applyIngest(insertOperation(), "# 같은 본문", new CountDownLatch(0));

        // 본문 해시로 재전송을 가리면 두 번째 기여가 사라져, 첫 작업을 되돌릴 때 페이지가 삭제된다.
        assertThat(count("SELECT count(*) FROM wiki_page_contributions WHERE page_id = ?")).isEqualTo(2L);
    }

    private Long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class, pageA);
    }

    // --- helpers ---

    private String insertOperation() {
        String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "INSERT INTO ai_operation_logs(operation_id, workspace_id, user_id, operation_type,"
                        + " status, changed_resource_count, created_at)"
                        + " VALUES (?, ?, ?, 'ingest', 'processing', 0, now())",
                operationId, workspaceId, userId);
        return operationId;
    }

    /** 콜백 수신 이후 단계만 재현한다. 저장소 읽기는 이미 끝났다고 보고 본문을 직접 넘긴다. */
    private Void applyIngest(String operationId, String markdown, CountDownLatch start) {
        await(start);
        String prefix = "wiki/" + workspaceId + "/pages/" + pageA + "/ops/" + operationId;
        OperationResultRequest request = new OperationResultRequest(
                operationId, "ingest", "succeeded", workspaceId, userId, null, "요약",
                List.of(), null);
        operationApplier.apply(operationId, request,
                List.of(new OperationApplier.LoadedPage(
                        pageA, prefix + ".md", prefix + ".json", markdown, "sha256:" + operationId)),
                "hash_" + operationId, Instant.now());
        return null;
    }

    private void insertPage(String pageId, String userId, String slug) {
        jdbcTemplate.update("""
                INSERT INTO wiki_pages(id, page_type, title, slug, status,
                                       user_id, workspace_id, created_at, updated_at)
                VALUES (?, 'concept', ?, ?, 'active', ?, ?, now(), now())
                """, pageId, slug, slug, userId, workspaceId);
    }

    /** 짧은 {@code lock_timeout}으로 잠금을 시도한다. 이미 잠겨 있으면 기다리지 않고 false. */
    private boolean tryLock(String pageId) {
        try (Connection connection = newConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '" + LOCK_WAIT + "'");
                statement.execute("SELECT id FROM wiki_pages WHERE id = '" + pageId + "' FOR UPDATE");
            }
            connection.rollback();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Void lockAllInOrder(List<String> pageIds, CountDownLatch start) {
        return transactionTemplate.execute(status -> {
            await(start);
            for (String pageId : pageIds) {
                wikiPageRepository.findByIdForUpdate(pageId).orElseThrow();
            }
            return null;
        });
    }

    /**
     * 두 행을 주어진 순서로 잠근다. 두 스레드가 첫 행을 각각 잡은 뒤에 두 번째를 시도해야
     * 교착이 재현되므로, 그 지점에서 서로를 기다린다.
     *
     * @return 두 행을 모두 잠그고 커밋했으면 true
     */
    private boolean lockPairWithRawConnection(String first, String second, CountDownLatch bothLockedFirst) {
        try (Connection connection = newConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT id FROM wiki_pages WHERE id = '" + first + "' FOR UPDATE");
                bothLockedFirst.countDown();
                if (!bothLockedFirst.await(5, TimeUnit.SECONDS)) {
                    return false;
                }
                statement.execute("SELECT id FROM wiki_pages WHERE id = '" + second + "' FOR UPDATE");
            }
            connection.commit();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간을 초과했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
