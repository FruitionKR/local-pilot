package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.EmailVerification;
import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.repository.EmailVerificationRepository;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailChangeTokenConcurrencyTest {
    @Autowired AuthService authService;
    @Autowired UserRepository users;
    @Autowired EmailVerificationRepository verifications;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameTokenAcrossProviders_onlyFirstChangeCommits() throws Exception {
        users.saveAndFlush(new User("token_local", "local@example.com", "local", "사용자", null));
        users.saveAndFlush(new User("token_google", "google@example.com", "google", "사용자", null));
        String token = "concurrent-email-change-token";
        var verification = new EmailVerification("ev_concurrent", "target@example.com", "email_change",
                OpaqueTokens.sha256("123456"), Instant.now().plusSeconds(300));
        verification.confirm(OpaqueTokens.sha256(token), Instant.now().plusSeconds(300));
        verifications.saveAndFlush(verification);
        var request = new EmailChangeRequest("target@example.com", token);

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = new TransactionTemplate(transactionManager).execute(status -> {
                authService.changeEmail("token_local", request, null);
                Future<?> competing = executor.submit(() -> authService.changeEmail("token_google", request, null));
                // 첫 소비를 미커밋 상태로 유지하고, 두 번째 요청의 실제 DB 잠금 대기를 확인한다.
                awaitTokenLockWait();
                return competing;
            });
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidVerificationTokenException.class);
            assertThat(users.findById("token_local").orElseThrow().getEmail()).isEqualTo("target@example.com");
            assertThat(users.findById("token_google").orElseThrow().getEmail()).isEqualTo("google@example.com");
            assertThat(verifications.findById("ev_concurrent").orElseThrow().isConsumed()).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitTokenLockWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // 트랜잭션 안의 통계 스냅샷을 갱신해 다른 연결의 현재 대기를 조회한다.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query LIKE '%email_verifications%')
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("두 번째 토큰 소비 요청이 DB 잠금 대기에 도달하지 않았습니다.");
    }
}
