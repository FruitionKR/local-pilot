package fruition.user.service;

import fruition.TestcontainersConfiguration;
import fruition.user.domain.EmailVerification;
import fruition.user.dto.EmailVerificationRequest;
import fruition.user.dto.VerificationConfirmRequest;
import fruition.user.exception.InvalidVerificationCodeException;
import fruition.user.exception.VerificationCodeAttemptsExceededException;
import fruition.user.repository.EmailVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mockito 단위 테스트는 in-memory 엔티티의 attempt_count 증가만 확인할 뿐,
 * 코드 오입력 시 예외를 던지면서도 증가분을 커밋으로 유지하는 confirm()의
 * {@code @Transactional(noRollbackFor = ...)} 동작은 확인하지 못한다.
 * 이 테스트는 Testcontainers Postgres에서 실제 트랜잭션 커밋 후 attempt_count가
 * 유지되고 max-attempts 초과 시 차단되는지 검증한다.
 *
 * <p>테스트 자체를 @Transactional로 감싸지 않는다 — service.confirm()의 트랜잭션 경계를
 * 그대로 타야 noRollbackFor의 커밋 여부를 검증할 수 있기 때문이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "app.auth.email-verification.dev-fixed-code=135790",
        "app.auth.email-verification.max-attempts=3"
})
class EmailVerificationConfirmIntegrationTest {

    @Autowired EmailVerificationService emailVerificationService;
    @Autowired EmailVerificationRepository verificationRepository;

    @Test
    void wrongCode_persistsAttemptCountAcrossException_andBlocksAfterMaxAttempts() {
        // dev-fixed-code=135790이 정답 코드. password_reset은 사용자 존재 없이 발급 가능.
        String verificationId = emailVerificationService
                .request(new EmailVerificationRequest("reset@example.com", "password_reset"))
                .verificationId();

        // 오입력 1회 → 예외를 던지지만 attempt_count는 커밋되어야 한다(noRollbackFor).
        assertThatThrownBy(() -> emailVerificationService.confirm(verificationId, new VerificationConfirmRequest("000000")))
                .isInstanceOf(InvalidVerificationCodeException.class);
        assertThat(reloadAttemptCount(verificationId)).isEqualTo(1);

        // 오입력 2회, 3회 → 누적 유지.
        assertThatThrownBy(() -> emailVerificationService.confirm(verificationId, new VerificationConfirmRequest("000000")))
                .isInstanceOf(InvalidVerificationCodeException.class);
        assertThat(reloadAttemptCount(verificationId)).isEqualTo(2);

        assertThatThrownBy(() -> emailVerificationService.confirm(verificationId, new VerificationConfirmRequest("000000")))
                .isInstanceOf(InvalidVerificationCodeException.class);
        assertThat(reloadAttemptCount(verificationId)).isEqualTo(3);

        // max-attempts(3) 도달 후에는 정답 코드를 넣어도 차단된다.
        assertThatThrownBy(() -> emailVerificationService.confirm(verificationId, new VerificationConfirmRequest("135790")))
                .isInstanceOf(VerificationCodeAttemptsExceededException.class);
    }

    private int reloadAttemptCount(String verificationId) {
        EmailVerification verification = verificationRepository.findById(verificationId).orElseThrow();
        return verification.getAttemptCount();
    }
}
