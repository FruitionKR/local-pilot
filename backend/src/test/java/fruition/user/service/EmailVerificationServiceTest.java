package fruition.user.service;

import fruition.user.domain.EmailVerification;
import fruition.user.dto.EmailVerificationRequest;
import fruition.user.dto.EmailVerificationResponse;
import fruition.user.dto.VerificationConfirmRequest;
import fruition.user.dto.VerificationConfirmResponse;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.exception.EmailVerificationNotFoundException;
import fruition.user.exception.InvalidVerificationCodeException;
import fruition.user.exception.InvalidVerificationTokenException;
import fruition.user.exception.VerificationCodeAttemptsExceededException;
import fruition.user.exception.VerificationCodeExpiredException;
import fruition.user.exception.VerificationRateLimitedException;
import fruition.user.mail.EmailVerificationSender;
import fruition.user.repository.EmailVerificationRepository;
import fruition.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock EmailVerificationRepository verificationRepository;
    @Mock UserRepository userRepository;
    @Mock EmailVerificationSender sender;

    EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                verificationRepository, userRepository, sender,
                300, 600, 60, 5, 5, "");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EmailVerification verification(String email, String purpose, String code, Instant codeExpiresAt) {
        return new EmailVerification("ev_1", email, purpose, sha256(code), codeExpiresAt);
    }

    // ----- request -----

    @Test
    void request_signupDuplicateEmail_throws() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.request(new EmailVerificationRequest("test@example.com", "signup")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void request_withinCooldown_throwsRateLimited() {
        EmailVerification recent = verification("test@example.com", "password_reset", "123456",
                Instant.now().plusSeconds(300));
        when(verificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc("test@example.com", "password_reset"))
                .thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.request(new EmailVerificationRequest("test@example.com", "password_reset")))
                .isInstanceOf(VerificationRateLimitedException.class);
    }

    @Test
    void request_dailyLimitReached_throwsRateLimitedWithWindowRetryAfter() {
        when(verificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(verificationRepository.countByEmailAndPurposeAndCreatedAtAfter(anyString(), anyString(), any()))
                .thenReturn(5L);
        // 윈도 내 최고령 요청(방금 생성) 기준이면 retryAfter는 24h(=86400s)에 가깝다.
        when(verificationRepository.findFirstByEmailAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(anyString(), anyString(), any()))
                .thenReturn(Optional.of(verification("test@example.com", "password_reset", "123456",
                        Instant.now().plusSeconds(300))));

        assertThatThrownBy(() -> service.request(new EmailVerificationRequest("test@example.com", "password_reset")))
                .isInstanceOf(VerificationRateLimitedException.class)
                .satisfies(e -> assertThat(((VerificationRateLimitedException) e).getRetryAfter())
                        .isGreaterThan(0L)
                        .isLessThanOrEqualTo(86400L));
    }

    @Test
    void request_valid_savesAndSends() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(verificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(verificationRepository.countByEmailAndPurposeAndCreatedAtAfter(anyString(), anyString(), any()))
                .thenReturn(0L);
        when(verificationRepository.findByEmailAndPurposeAndConsumedAtIsNull(anyString(), anyString()))
                .thenReturn(List.of());

        EmailVerificationResponse response = service.request(new EmailVerificationRequest("test@example.com", "signup"));

        assertThat(response.verificationId()).startsWith("ev_");
        assertThat(response.expiresIn()).isEqualTo(300);
        assertThat(response.retryAfter()).isEqualTo(60);
        verify(verificationRepository).save(any(EmailVerification.class));
        verify(sender).send(eq("test@example.com"), eq("signup"), anyString());
    }

    // ----- confirm -----

    @Test
    void confirm_notFound_throws() {
        when(verificationRepository.findById("ev_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("ev_x", new VerificationConfirmRequest("123456")))
                .isInstanceOf(EmailVerificationNotFoundException.class);
    }

    @Test
    void confirm_expired_throws() {
        EmailVerification expired = verification("test@example.com", "signup", "123456",
                Instant.now().minusSeconds(10));
        when(verificationRepository.findById("ev_1")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.confirm("ev_1", new VerificationConfirmRequest("123456")))
                .isInstanceOf(VerificationCodeExpiredException.class);
    }

    @Test
    void confirm_wrongCode_incrementsAttemptAndThrows() {
        EmailVerification v = verification("test@example.com", "signup", "123456",
                Instant.now().plusSeconds(300));
        when(verificationRepository.findById("ev_1")).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.confirm("ev_1", new VerificationConfirmRequest("000000")))
                .isInstanceOf(InvalidVerificationCodeException.class);
        assertThat(v.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void confirm_attemptsExceeded_throws() {
        EmailVerification v = verification("test@example.com", "signup", "123456",
                Instant.now().plusSeconds(300));
        for (int i = 0; i < 5; i++) {
            v.increaseAttempt();
        }
        when(verificationRepository.findById("ev_1")).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.confirm("ev_1", new VerificationConfirmRequest("123456")))
                .isInstanceOf(VerificationCodeAttemptsExceededException.class);
    }

    @Test
    void confirm_correctCode_issuesToken() {
        EmailVerification v = verification("test@example.com", "signup", "123456",
                Instant.now().plusSeconds(300));
        when(verificationRepository.findById("ev_1")).thenReturn(Optional.of(v));

        VerificationConfirmResponse response = service.confirm("ev_1", new VerificationConfirmRequest("123456"));

        assertThat(response.verificationToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(600);
        assertThat(v.isTokenValid()).isTrue();
    }

    // ----- consume -----

    @Test
    void consumeForSignup_unknownToken_throws() {
        when(verificationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeForSignup("test@example.com", "bad-token"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void consumeForSignup_purposeMismatch_throws() {
        EmailVerification v = verification("test@example.com", "password_reset", "123456",
                Instant.now().plusSeconds(300));
        v.confirm(sha256("good-token"), Instant.now().plusSeconds(600));
        when(verificationRepository.findByTokenHash(sha256("good-token"))).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.consumeForSignup("test@example.com", "good-token"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void consumeForSignup_valid_consumesToken() {
        EmailVerification v = verification("test@example.com", "signup", "123456",
                Instant.now().plusSeconds(300));
        v.confirm(sha256("good-token"), Instant.now().plusSeconds(600));
        when(verificationRepository.findByTokenHash(sha256("good-token"))).thenReturn(Optional.of(v));

        service.consumeForSignup("test@example.com", "good-token");

        assertThat(v.isConsumed()).isTrue();
    }

    @Test
    void consumeForSignup_reusedToken_throws() {
        EmailVerification v = verification("test@example.com", "signup", "123456",
                Instant.now().plusSeconds(300));
        v.confirm(sha256("good-token"), Instant.now().plusSeconds(600));
        v.consume();
        when(verificationRepository.findByTokenHash(sha256("good-token"))).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.consumeForSignup("test@example.com", "good-token"))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }
}
