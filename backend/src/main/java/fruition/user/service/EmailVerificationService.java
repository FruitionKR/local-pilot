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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final String PURPOSE_SIGNUP = "signup";
    private static final String PURPOSE_PASSWORD_RESET = "password_reset";

    private final EmailVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final EmailVerificationSender sender;
    private final SecureRandom secureRandom = new SecureRandom();

    private final long codeTtlSeconds;
    private final long tokenTtlSeconds;
    private final long resendCooldownSeconds;
    private final long dailyLimit;
    private final int maxAttempts;
    private final String devFixedCode;

    public EmailVerificationService(
            EmailVerificationRepository verificationRepository,
            UserRepository userRepository,
            EmailVerificationSender sender,
            @Value("${app.auth.email-verification.code-ttl-seconds}") long codeTtlSeconds,
            @Value("${app.auth.email-verification.token-ttl-seconds}") long tokenTtlSeconds,
            @Value("${app.auth.email-verification.resend-cooldown-seconds}") long resendCooldownSeconds,
            @Value("${app.auth.email-verification.daily-limit}") long dailyLimit,
            @Value("${app.auth.email-verification.max-attempts}") int maxAttempts,
            @Value("${app.auth.email-verification.dev-fixed-code:}") String devFixedCode) {
        this.verificationRepository = verificationRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.codeTtlSeconds = codeTtlSeconds;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.dailyLimit = dailyLimit;
        this.maxAttempts = maxAttempts;
        this.devFixedCode = devFixedCode;
    }

    @Transactional
    public EmailVerificationResponse request(EmailVerificationRequest request) {
        String email = request.email().trim().toLowerCase();
        String purpose = request.purpose();

        // 회원가입은 의도적으로 존재 여부를 노출한다. 비밀번호 재설정은 노출하지 않는다.
        if (PURPOSE_SIGNUP.equals(purpose) && userRepository.existsByEmail(email)) {
            log.warn("[인증 요청 거부] reason=duplicate_email email={}", email);
            throw new DuplicateEmailException(email);
        }

        enforceRateLimit(email, purpose);

        // 새 코드 발급 전 같은 (email, purpose)의 미소비 코드를 폐기한다.
        for (EmailVerification previous : verificationRepository.findByEmailAndPurposeAndConsumedAtIsNull(email, purpose)) {
            previous.expireCode();
        }

        String code = devFixedCode.isBlank() ? generateCode() : devFixedCode;
        String id = "ev_" + UUID.randomUUID().toString().replace("-", "");
        EmailVerification verification = new EmailVerification(
                id, email, purpose, sha256(code), Instant.now().plusSeconds(codeTtlSeconds));
        verificationRepository.save(verification);

        sender.send(email, purpose, code);
        log.info("[인증 요청] verificationId={} email={} purpose={}", id, email, purpose);

        return new EmailVerificationResponse(id, codeTtlSeconds, resendCooldownSeconds);
    }

    // 코드 오입력 시 attempt_count 증가를 유지하기 위해 InvalidVerificationCodeException은 롤백하지 않는다.
    @Transactional(noRollbackFor = InvalidVerificationCodeException.class)
    public VerificationConfirmResponse confirm(String verificationId, VerificationConfirmRequest request) {
        EmailVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(EmailVerificationNotFoundException::new);

        if (verification.isConsumed()) {
            throw new InvalidVerificationCodeException();
        }
        if (verification.getAttemptCount() >= maxAttempts) {
            throw new VerificationCodeAttemptsExceededException();
        }
        if (verification.isCodeExpired()) {
            throw new VerificationCodeExpiredException();
        }
        if (!verification.getCodeHash().equals(sha256(request.code()))) {
            verification.increaseAttempt();
            log.warn("[인증번호 검증 실패] verificationId={} attempt={}", verificationId, verification.getAttemptCount());
            throw new InvalidVerificationCodeException();
        }

        String token = generateOpaqueToken();
        verification.confirm(sha256(token), Instant.now().plusSeconds(tokenTtlSeconds));
        log.info("[인증번호 검증 성공] verificationId={} email={}", verification.getId(), verification.getEmail());

        return new VerificationConfirmResponse(token, tokenTtlSeconds);
    }

    @Transactional
    public void consumeForSignup(String email, String token) {
        consumeToken(email, token, PURPOSE_SIGNUP);
    }

    @Transactional
    public void consumeForPasswordReset(String email, String token) {
        consumeToken(email, token, PURPOSE_PASSWORD_RESET);
    }

    private void consumeToken(String email, String token, String purpose) {
        EmailVerification verification = verificationRepository.findByTokenHash(sha256(token))
                .orElseThrow(InvalidVerificationTokenException::new);

        if (!verification.getPurpose().equals(purpose)
                || !verification.getEmail().equals(email)
                || !verification.isTokenValid()) {
            throw new InvalidVerificationTokenException();
        }

        verification.consume();
    }

    private void enforceRateLimit(String email, String purpose) {
        verificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .ifPresent(last -> {
                    long elapsed = Duration.between(last.getCreatedAt(), Instant.now()).getSeconds();
                    if (elapsed < resendCooldownSeconds) {
                        throw new VerificationRateLimitedException(resendCooldownSeconds - elapsed);
                    }
                });

        long dayCount = verificationRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, Instant.now().minus(Duration.ofDays(1)));
        if (dayCount >= dailyLimit) {
            throw new VerificationRateLimitedException(resendCooldownSeconds);
        }
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
