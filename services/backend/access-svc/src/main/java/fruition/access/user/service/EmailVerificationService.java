package fruition.access.user.service;

import fruition.access.user.domain.EmailVerification;
import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.EmailVerificationResponse;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.dto.VerificationConfirmResponse;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.EmailVerificationNotFoundException;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.VerificationCodeAttemptsExceededException;
import fruition.access.user.exception.VerificationCodeExpiredException;
import fruition.access.user.exception.VerificationRateLimitedException;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.repository.EmailVerificationRepository;
import fruition.access.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
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
            TransactionTemplate transactionTemplate,
            @Value("${app.auth.email-verification.code-ttl-seconds}") long codeTtlSeconds,
            @Value("${app.auth.email-verification.token-ttl-seconds}") long tokenTtlSeconds,
            @Value("${app.auth.email-verification.resend-cooldown-seconds}") long resendCooldownSeconds,
            @Value("${app.auth.email-verification.daily-limit}") long dailyLimit,
            @Value("${app.auth.email-verification.max-attempts}") int maxAttempts,
            @Value("${app.auth.email-verification.dev-fixed-code:}") String devFixedCode) {
        this.verificationRepository = verificationRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.transactionTemplate = transactionTemplate;
        this.codeTtlSeconds = codeTtlSeconds;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.resendCooldownSeconds = resendCooldownSeconds;
        this.dailyLimit = dailyLimit;
        this.maxAttempts = maxAttempts;
        this.devFixedCode = devFixedCode;
    }

    public EmailVerificationResponse request(EmailVerificationRequest request) {
        String email = request.email().trim().toLowerCase();
        String purpose = request.purpose();

        // 존재 노출(signup 409)도 throttle 대상이 되도록 rate limit을 먼저 적용한다.
        // 단 per-(email, purpose) 범위라 서로 다른 이메일 대량 열거는 못 막으며, 그건 IP/전역 제한 영역이다.
        enforceRateLimit(email, purpose);

        // 회원가입은 의도적으로 존재 여부를 노출한다. 비밀번호 재설정은 노출하지 않는다.
        if (PURPOSE_SIGNUP.equals(purpose) && userRepository.existsByEmailAndProvider(email, User.PROVIDER_LOCAL)) {
            log.warn("[인증 요청 거부] reason=duplicate_email email={}", email);
            throw new DuplicateEmailException(email);
        }

        String code = devFixedCode.isBlank() ? generateCode() : devFixedCode;
        String id = "ev_" + UUID.randomUUID().toString().replace("-", "");

        // DB 쓰기만 트랜잭션으로 처리하고 커밋한다. SMTP 발송을 트랜잭션 안에서 하면
        // 외부 메일 서버 왕복 동안 DB 커넥션을 붙잡으므로, 발송은 커밋 후 트랜잭션 밖에서 한다.
        transactionTemplate.executeWithoutResult(status -> {
            // 새 코드 발급 전 같은 (email, purpose)의 미소비 코드를 폐기한다.
            for (EmailVerification previous : verificationRepository.findByEmailAndPurposeAndConsumedAtIsNull(email, purpose)) {
                previous.expireCode();
            }
            verificationRepository.save(new EmailVerification(
                    id, email, purpose, sha256(code), Instant.now().plusSeconds(codeTtlSeconds)));
        });

        // 발송 실패 시 예외를 전파한다(레코드는 남지만 재요청 시 폐기되고 TTL로 만료된다).
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

        long dayWindowSeconds = Duration.ofDays(1).toSeconds();
        Instant since = Instant.now().minusSeconds(dayWindowSeconds);
        long dayCount = verificationRepository.countByEmailAndPurposeAndCreatedAtAfter(email, purpose, since);
        if (dayCount >= dailyLimit) {
            // 가장 오래된 요청이 24h 윈도를 벗어나야 카운트가 줄어드므로, 그 시점까지를 retryAfter로 준다.
            long retryAfter = verificationRepository
                    .findFirstByEmailAndPurposeAndCreatedAtAfterOrderByCreatedAtAsc(email, purpose, since)
                    .map(oldest -> Duration.between(Instant.now(), oldest.getCreatedAt().plusSeconds(dayWindowSeconds)).getSeconds())
                    .filter(seconds -> seconds > 0)
                    .orElse(resendCooldownSeconds);
            throw new VerificationRateLimitedException(retryAfter);
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
