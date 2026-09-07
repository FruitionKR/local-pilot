package fruition.access.user.mfa;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.*;
import fruition.access.user.dto.MfaLoginRequest;
import fruition.access.user.exception.*;
import fruition.access.user.repository.*;
import fruition.access.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MfaConcurrencyIntegrationTest {
    @Autowired AuthService auth;
    @Autowired MfaService mfaService;
    @Autowired MfaSecretCipher cipher;
    @Autowired UserRepository users;
    @Autowired UserMfaRepository mfas;
    @Autowired UserMfaChallengeRepository challenges;
    @Autowired UserMfaRecoveryCodeRepository recoveryCodes;
    @Autowired UserRefreshTokenRepository refreshTokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        var encrypted = cipher.encrypt(new byte[20]);
        var mfa = new UserMfa(id, encrypted.ciphertext(), encrypted.nonce());
        mfa.activate(Instant.now().getEpochSecond() / 30 - 2);
        mfas.saveAndFlush(mfa);
        return id;
    }

    private String challenge(String id) {
        String token = UUID.randomUUID().toString();
        challenges.saveAndFlush(new UserMfaChallenge(token, id, OpaqueTokens.sha256(token), Instant.now().plusSeconds(300)));
        return token;
    }

    /** TOTP·복구 코드·challenge 각각의 중복 소비를 서로 다른 요청으로 검증한다. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void concurrentLogin_onlyOneSucceeds(int scenario) throws Exception {
        String id = user();
        String firstToken = challenge(id);
        String secondToken = scenario == 2 ? firstToken : challenge(id);
        String firstCode;
        if (scenario == 0) {
            firstCode = String.format("%06d", new TimeBasedOneTimePasswordGenerator()
                    .generateOneTimePassword(new SecretKeySpec(new byte[20], "HmacSHA1"), Instant.now()));
        } else {
            firstCode = "recovery-first";
            recoveryCodes.saveAndFlush(new UserMfaRecoveryCode(id, OpaqueTokens.sha256(firstCode)));
        }
        String secondCode = scenario == 2 ? "recovery-second" : firstCode;
        if (scenario == 2) recoveryCodes.saveAndFlush(new UserMfaRecoveryCode(id, OpaqueTokens.sha256(secondCode)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = new TransactionTemplate(transactions).execute(status -> {
                auth.loginMfa(new MfaLoginRequest(firstToken, firstCode));
                Future<?> result = executor.submit(() -> auth.loginMfa(new MfaLoginRequest(secondToken, secondCode)));
                awaitLock();
                return result;
            });
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(
                    scenario == 2 ? InvalidMfaChallengeException.class : InvalidMfaCodeException.class);
            assertThat(refreshTokens.findAllByUserIdAndRevokedAtIsNull(id)).hasSize(1);
            if (scenario == 2) assertThat(recoveryCodes.findAllByUserIdAndConsumedAtIsNull(id)).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedAttempts_surviveRollbackAndChallengeReissue() {
        String id = user();
        for (int i = 0; i < 5; i++) {
            String token = challenge(id);
            assertThatThrownBy(() -> auth.loginMfa(new MfaLoginRequest(token, "invalid")))
                    .isInstanceOf(InvalidMfaCodeException.class);
        }
        String token = challenge(id);
        assertThatThrownBy(() -> auth.loginMfa(new MfaLoginRequest(token, "invalid")))
                .isInstanceOf(MfaRateLimitedException.class);
        assertThatThrownBy(() -> mfaService.verify(id, "invalid"))
                .isInstanceOf(MfaRateLimitedException.class);
        assertThat(redis.getExpire("auth:mfa:attempts:" + id)).isBetween(1L, 300L);
        redis.expire("auth:mfa:attempts:" + id, java.time.Duration.ZERO);
        assertThatThrownBy(() -> auth.loginMfa(new MfaLoginRequest(token, "invalid")))
                .isInstanceOf(InvalidMfaCodeException.class);
        String other = challenge(user());
        assertThatThrownBy(() -> auth.loginMfa(new MfaLoginRequest(other, "invalid")))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    private void awaitLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%user_mfa%')
                    """, Boolean.class))) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("두 번째 MFA 요청이 DB 잠금 대기에 도달하지 않았습니다.");
    }
}
