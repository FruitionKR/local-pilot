package fruition.access.user.mfa;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfa;
import fruition.access.user.domain.UserMfaRecoveryCode;
import fruition.access.user.dto.MfaRegistrationResponse;
import fruition.access.user.exception.InvalidMfaCodeException;
import fruition.access.user.exception.MfaAlreadyEnabledException;
import fruition.access.user.exception.MfaNotEnabledException;
import fruition.access.user.repository.UserMfaRecoveryCodeRepository;
import fruition.access.user.repository.UserMfaRepository;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaServiceTest {

    private static final String USER_ID = "user_1";
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Mock UserRepository userRepository;
    @Mock UserMfaRepository mfaRepository;
    @Mock UserMfaRecoveryCodeRepository recoveryCodeRepository;

    MfaSecretCipher cipher;
    MfaService service;
    final List<UserMfaRecoveryCode> savedCodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cipher = new MfaSecretCipher(KEY);
        service = new MfaService(userRepository, mfaRepository, recoveryCodeRepository, cipher, "Fruition");
        savedCodes.clear();
        lenient().when(recoveryCodeRepository.saveAll(any())).thenAnswer(invocation -> {
            invocation.<Iterable<UserMfaRecoveryCode>>getArgument(0).forEach(savedCodes::add);
            return savedCodes;
        });
        lenient().when(mfaRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void user() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                new User(USER_ID, "user@example.com", User.PROVIDER_LOCAL, "이름", "hash")));
    }

    /** 등록 응답의 base32 secret으로 실제 코드를 만들어 검증 경로를 그대로 통과시킨다. */
    private String codeFor(String base32Secret, Instant at) throws Exception {
        byte[] secret = decodeBase32(base32Secret);
        var totp = new TimeBasedOneTimePasswordGenerator();
        return String.format("%06d",
                totp.generateOneTimePassword(new SecretKeySpec(secret, "HmacSHA1"), at));
    }

    private static byte[] decodeBase32(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0;
        int bitsLeft = 0;
        var out = new java.io.ByteArrayOutputStream();
        for (char character : encoded.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(character);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private UserMfa registerAndCapture() {
        user();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.empty());
        MfaRegistrationResponse response = service.register(USER_ID);
        var captor = org.mockito.ArgumentCaptor.forClass(UserMfa.class);
        verify(mfaRepository).save(captor.capture());
        registered = response;
        return captor.getValue();
    }

    MfaRegistrationResponse registered;

    @Test
    void register_issuesSecretAndRecoveryCodes() {
        registerAndCapture();

        assertThat(registered.secret()).isNotBlank();
        assertThat(registered.otpauthUri()).startsWith("otpauth://totp/").contains("issuer=Fruition");
        assertThat(registered.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        // 복구 코드는 해시만 저장한다.
        assertThat(savedCodes).hasSize(10);
        assertThat(savedCodes.get(0).getCodeHash())
                .isEqualTo(OpaqueTokens.sha256(registered.recoveryCodes().get(0)));
        // 재등록 시 옛 코드는 버린다.
        verify(recoveryCodeRepository).deleteAllByUserId(USER_ID);
    }

    /** 등록만으로는 켜지지 않는다 — 로그인을 막으면 QR을 잘못 스캔한 사용자가 잠긴다. */
    @Test
    void register_doesNotActivate() {
        UserMfa mfa = registerAndCapture();

        assertThat(mfa.isActivated()).isFalse();
    }

    @Test
    void activate_withValidCodeTurnsOn() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));

        service.activate(USER_ID, codeFor(registered.secret(), Instant.now()));

        assertThat(mfa.isActivated()).isTrue();
    }

    @Test
    void activate_withWrongCodeThrows() {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));

        assertThatThrownBy(() -> service.activate(USER_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);
        assertThat(mfa.isActivated()).isFalse();
    }

    /** 같은 30초 창의 코드를 두 번 쓰면 안 된다 — 가로챈 코드의 재사용을 막는다. */
    @Test
    void verify_rejectsReuseOfSameTimeStep() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));
        String code = codeFor(registered.secret(), Instant.now());
        service.activate(USER_ID, code);

        assertThatThrownBy(() -> service.verify(USER_ID, code))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void verify_acceptsRecoveryCodeAndConsumesIt() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));
        service.activate(USER_ID, codeFor(registered.secret(), Instant.now()));
        String recovery = registered.recoveryCodes().get(3);
        when(recoveryCodeRepository.findAllByUserIdAndConsumedAtIsNull(USER_ID)).thenReturn(savedCodes);

        service.verify(USER_ID, recovery);

        UserMfaRecoveryCode used = savedCodes.stream()
                .filter(code -> code.getCodeHash().equals(OpaqueTokens.sha256(recovery)))
                .findFirst().orElseThrow();
        assertThat(used.getConsumedAt()).isNotNull();
    }

    @Test
    void verify_notActivatedThrows() {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));

        assertThatThrownBy(() -> service.verify(USER_ID, "000000"))
                .isInstanceOf(MfaNotEnabledException.class);
    }

    @Test
    void register_whenAlreadyActivatedThrows() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));
        service.activate(USER_ID, codeFor(registered.secret(), Instant.now()));

        assertThatThrownBy(() -> service.register(USER_ID))
                .isInstanceOf(MfaAlreadyEnabledException.class);
    }

    @Test
    void disable_removesSecretAndRecoveryCodes() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));
        service.activate(USER_ID, codeFor(registered.secret(), Instant.now()));

        service.disable(USER_ID);

        verify(mfaRepository).delete(mfa);
        verify(recoveryCodeRepository, org.mockito.Mockito.times(2)).deleteAllByUserId(USER_ID);
    }

    @Test
    void isEnabled_falseUntilActivated() throws Exception {
        UserMfa mfa = registerAndCapture();
        when(mfaRepository.findById(USER_ID)).thenReturn(Optional.of(mfa));

        assertThat(service.isEnabled(USER_ID)).isFalse();
        service.activate(USER_ID, codeFor(registered.secret(), Instant.now()));
        assertThat(service.isEnabled(USER_ID)).isTrue();
    }
}
