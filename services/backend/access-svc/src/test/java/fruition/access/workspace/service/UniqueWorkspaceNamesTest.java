package fruition.access.workspace.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class UniqueWorkspaceNamesTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (id, display_name, email, provider, created_at, updated_at)
                VALUES (?, '소유자', ?, 'local', now(), now())
                """, id, id + "@example.com");
        return id;
    }

    private String workspace(String owner, String name) {
        return transaction.execute(status -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO workspaces (id, name, created_at, updated_at) VALUES (?, ?, now(), now())", id, name);
            member(id, owner, "OWNER");
            return id;
        });
    }

    private void member(String workspace, String user, String role) {
        jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, joined_at) VALUES (?, ?, ?, now())",
                workspace, user, role);
    }

    @Test
    void sameOwnerCannotCreateCanonicalDuplicateButDifferentOwnersCan() {
        String owner = user();
        workspace(owner, "자료 API");
        assertThatThrownBy(() -> workspace(owner, " 자료 api "))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_workspaces_owner_active_name");
        workspace(user(), "자료 API");
    }

    @Test
    void renameDeleteAndRestoreMaintainReservations() {
        String owner = user();
        String first = workspace(owner, "운영");
        String second = workspace(owner, "개발");
        assertThatThrownBy(() -> jdbc.update("UPDATE workspaces SET name = '운영' WHERE id = ?", second))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE workspaces SET deleted_at = now() WHERE id = ?", first);
        jdbc.update("UPDATE workspaces SET name = '운영' WHERE id = ?", second);
        assertThatThrownBy(() -> jdbc.update("UPDATE workspaces SET deleted_at = NULL WHERE id = ?", first))
                .isInstanceOf(DuplicateKeyException.class);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", second);
        jdbc.update("UPDATE workspaces SET deleted_at = NULL WHERE id = ?", first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_name_reservations WHERE user_id = ?", Integer.class, owner))
                .isEqualTo(1);
    }

    @Test
    void addingOrPromotingOwnerChecksAllTheirWorkspaceNames() {
        String owner = user();
        workspace(owner, "자료");
        String other = workspace(user(), "자료");
        assertThatThrownBy(() -> member(other, owner, "OWNER")).isInstanceOf(DuplicateKeyException.class);
        member(other, owner, "MEMBER");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workspace_members SET role = 'OWNER' WHERE workspace_id = ? AND user_id = ?", other, owner))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ?",
                String.class, other, owner)).isEqualTo("MEMBER");
    }

    @Test
    void removingOwnerReleasesOnlyTheirReservation() {
        String firstOwner = user();
        String secondOwner = user();
        String workspace = workspace(firstOwner, "공동");
        member(workspace, secondOwner, "OWNER");
        jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", workspace, firstOwner);
        workspace(firstOwner, "공동");
        assertThatThrownBy(() -> workspace(secondOwner, "공동")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void concurrentOwnerAddsPreserveForeignKeyLocksWithoutDeadlock() throws Exception {
        String workspace = workspace(user(), "동시 소유자 추가");
        String firstOwner = user();
        String secondOwner = user();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.function.Function<String, java.util.concurrent.Callable<Boolean>> addOwner = owner -> () ->
                    transaction.execute(status -> {
                        // FK 검증이 얻는 KEY SHARE를 두 요청 모두 보유한 상태에서 트리거를 실행한다.
                        jdbc.queryForObject("SELECT id FROM workspaces WHERE id = ? FOR KEY SHARE", String.class, workspace);
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                        member(workspace, owner, "OWNER");
                        return true;
                    });
            var first = executor.submit(addOwner.apply(firstOwner));
            var second = executor.submit(addOwner.apply(secondOwner));
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_name_reservations WHERE workspace_id = ?",
                Integer.class, workspace)).isEqualTo(3);
    }

    @Test
    void concurrentCreatesCommitExactlyOneWorkspaceWithoutOrphans() throws Exception {
        String owner = user();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> create = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    workspace(owner, "동시 생성");
                    return true;
                } catch (DuplicateKeyException expected) {
                    return false;
                }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE name = '동시 생성'", Integer.class))
                .isEqualTo(1);
    }
}
