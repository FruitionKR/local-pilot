package fruition.access.user.mfa;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfa;
import fruition.access.user.domain.UserMfaRecoveryCode;
import fruition.access.user.dto.MfaRegistrationResponse;
import fruition.access.user.dto.MfaStatusResponse;
import fruition.access.user.exception.InvalidMfaCodeException;
import fruition.access.user.exception.MfaAlreadyEnabledException;
import fruition.access.user.exception.MfaNotEnabledException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserMfaRecoveryCodeRepository;
import fruition.access.user.repository.UserMfaRepository;
import fruition.access.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * TOTP 등록·검증.
 *
 * <p>코드 생성만 라이브러리에 맡기고 정책은 여기서 다룬다 — 앞뒤 한 창을 허용하는 시계 오차 보정,
 * 같은 창의 코드 재사용 차단, 복구 코드 소비가 그것이다.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final int SECRET_BYTES = 20;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_BYTES = 8;
    private static final long TIME_STEP_SECONDS = 30;
    /** 클라이언트 시계 오차를 감안해 앞뒤 한 창까지 받아준다. */
    private static final int ALLOWED_DRIFT_STEPS = 1;

    private final UserRepository userRepository;
    private final UserMfaRepository mfaRepository;
    private final UserMfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaSecretCipher cipher;
    private final TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator();
    private final SecureRandom secureRandom = new SecureRandom();
    private final String issuer;

    public MfaService(UserRepository userRepository,
                      UserMfaRepository mfaRepository,
                      UserMfaRecoveryCodeRepository recoveryCodeRepository,
                      MfaSecretCipher cipher,
                      @Value("${app.auth.mfa.issuer:Fruition}") String issuer) {
        this.userRepository = userRepository;
        this.mfaRepository = mfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.cipher = cipher;
        this.issuer = issuer;
    }

    /** 1단계: secret과 복구 코드를 발급한다. 아직 켜지지 않으며 로그인을 막지 않는다. */
    @Transactional
    public MfaRegistrationResponse register(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        mfaRepository.findById(userId).ifPresent(existing -> {
            if (existing.isActivated()) {
                throw new MfaAlreadyEnabledException();
            }
        });

        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        MfaSecretCipher.Encrypted encrypted = cipher.encrypt(secret);

        UserMfa mfa = mfaRepository.findById(userId)
                .map(existing -> {
                    existing.reissue(encrypted.ciphertext(), encrypted.nonce());
                    return existing;
                })
                .orElseGet(() -> mfaRepository.save(
                        new UserMfa(userId, encrypted.ciphertext(), encrypted.nonce())));

        // 재등록이면 옛 복구 코드는 옛 secret과 짝이므로 함께 버린다.
        recoveryCodeRepository.deleteAllByUserId(userId);
        List<String> recoveryCodes = generateRecoveryCodes();
        recoveryCodeRepository.saveAll(recoveryCodes.stream()
                .map(code -> new UserMfaRecoveryCode(userId, OpaqueTokens.sha256(code)))
                .toList());

        log.info("[MFA 등록 시작] userId={}", mfa.getUserId());
        return new MfaRegistrationResponse(
                Base32.encode(secret), otpauthUri(user, secret), recoveryCodes);
    }

    /** 2단계: 코드를 맞춰야 실제로 켜진다. 바로 켜면 QR을 잘못 스캔한 사용자가 잠긴다. */
    @Transactional
    public void activate(String userId, String code) {
        UserMfa mfa = mfaRepository.findById(userId).orElseThrow(MfaNotEnabledException::new);
        if (mfa.isActivated()) {
            throw new MfaAlreadyEnabledException();
        }
        long counter = matchingCounter(mfa, code).orElseThrow(InvalidMfaCodeException::new);
        mfa.activate(counter);
        log.info("[MFA 활성화] userId={}", userId);
    }

    /** 로그인 2단계와 해제에서 함께 쓴다. TOTP가 아니면 복구 코드로 한 번 더 본다. */
    @Transactional
    public void verify(String userId, String code) {
        UserMfa mfa = mfaRepository.findById(userId).orElseThrow(MfaNotEnabledException::new);
        if (!mfa.isActivated()) {
            throw new MfaNotEnabledException();
        }
        var counter = matchingCounter(mfa, code);
        if (counter.isPresent()) {
            mfa.markCounterUsed(counter.get());
            return;
        }
        consumeRecoveryCode(userId, code);
    }

    @Transactional
    public void disable(String userId) {
        UserMfa mfa = mfaRepository.findById(userId).orElseThrow(MfaNotEnabledException::new);
        recoveryCodeRepository.deleteAllByUserId(userId);
        mfaRepository.delete(mfa);
        log.info("[MFA 해제] userId={}", userId);
    }

    public boolean isEnabled(String userId) {
        return mfaRepository.findById(userId).filter(UserMfa::isActivated).isPresent();
    }

    public MfaStatusResponse status(String userId) {
        return mfaRepository.findById(userId)
                .filter(UserMfa::isActivated)
                .map(mfa -> new MfaStatusResponse(
                        true,
                        mfa.getActivatedAt(),
                        recoveryCodeRepository.findAllByUserIdAndConsumedAtIsNull(userId).size()))
                .orElseGet(() -> new MfaStatusResponse(false, null, 0));
    }

    /**
     * 허용 창 안에서 코드와 맞는 시간 창을 찾는다. 이미 쓴 창은 건너뛴다 —
     * 그렇지 않으면 코드를 가로챈 쪽이 같은 30초 안에 재사용할 수 있다.
     */
    private java.util.Optional<Long> matchingCounter(UserMfa mfa, String code) {
        String normalized = code == null ? "" : code.trim();
        if (normalized.length() != totp.getPasswordLength()) {
            return java.util.Optional.empty();
        }
        int expected;
        try {
            expected = Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
        SecretKeySpec key = new SecretKeySpec(
                cipher.decrypt(mfa.getSecretCipher(), mfa.getSecretNonce()), "HmacSHA1");
        long current = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long step = current - ALLOWED_DRIFT_STEPS; step <= current + ALLOWED_DRIFT_STEPS; step++) {
            if (mfa.isCounterUsed(step)) {
                continue;
            }
            try {
                if (totp.generateOneTimePassword(key, Instant.ofEpochSecond(step * TIME_STEP_SECONDS)) == expected) {
                    return java.util.Optional.of(step);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("TOTP 코드 계산에 실패했습니다.", exception);
            }
        }
        return java.util.Optional.empty();
    }

    private void consumeRecoveryCode(String userId, String code) {
        String hash = OpaqueTokens.sha256(code == null ? "" : code.trim());
        UserMfaRecoveryCode recoveryCode = recoveryCodeRepository
                .findAllByUserIdAndConsumedAtIsNull(userId).stream()
                .filter(candidate -> candidate.getCodeHash().equals(hash))
                .findFirst()
                .orElseThrow(InvalidMfaCodeException::new);
        recoveryCode.consume();
        log.info("[MFA 복구 코드 사용] userId={} recoveryCodeId={}", userId, recoveryCode.getId());
    }

    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            byte[] bytes = new byte[RECOVERY_CODE_BYTES];
            secureRandom.nextBytes(bytes);
            codes.add(Base32.encode(bytes));
        }
        return codes;
    }

    private String otpauthUri(User user, byte[] secret) {
        String label = URLEncoder.encode(issuer + ":" + user.getEmail(), StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8);
    }
}
