package fruition.access.user.service;

import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.DisplayNameUpdateRequest;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.dto.PasswordChangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.repository.UserRefreshTokenRepository;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserRefreshTokenRepository refreshTokenRepository;
    @Mock EmailVerificationService emailVerificationService;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            "test-only-jwt-secret-32-bytes-minimum-length", 900, "fruition-access", "fruition-api");
    OAuthExchangeCodeStore oAuthExchangeCodeStore = inMemoryExchangeCodeStore();
    AuthService authService;

    /** Redis 대신 in-memory Map으로 set/getAndDelete를 흉내 내 issue/consume 의미를 유지한다. */
    @SuppressWarnings("unchecked")
    private static OAuthExchangeCodeStore inMemoryExchangeCodeStore() {
        java.util.Map<String, String> data = new java.util.concurrent.ConcurrentHashMap<>();
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            data.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(any(String.class), any(String.class), any(java.time.Duration.class));
        org.mockito.Mockito.lenient().when(valueOps.getAndDelete(any(String.class)))
                .thenAnswer(invocation -> data.remove(invocation.<String>getArgument(0)));
        return new OAuthExchangeCodeStore(redisTemplate);
    }

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider,
                oAuthExchangeCodeStore, emailVerificationService, 1209600);
    }

    private User newUser(String rawPassword) {
        return new User("user_1f9a74af", "test@example.com", User.PROVIDER_LOCAL, "tes", passwordEncoder.encode(rawPassword));
    }

    @Test
    void login_correctPassword_issuesAccessAndRefreshToken() {
        when(userRepository.findByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(Optional.of(newUser("password123")));

        LoginResponse response = authService.login(new LoginRequest("test@example.com", "password123"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(Optional.of(newUser("password123")));

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmailAndProvider("nobody@example.com", User.PROVIDER_LOCAL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_validToken_rotatesAndIssuesNewTokens() {
        UserRefreshToken existing = new UserRefreshToken("user_1f9a74af", "any-hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));
        when(userRepository.findById("user_1f9a74af")).thenReturn(Optional.of(newUser("password123")));

        LoginResponse response = authService.refresh(new RefreshRequest("some-refresh-token"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(existing.getRevokedAt()).isNotNull();
    }

    @Test
    void resetPassword_validToken_changesPasswordAndRevokesRefreshTokens() {
        User user = newUser("old-password");
        UserRefreshToken active = new UserRefreshToken("user_1f9a74af", "hash", Instant.now().plusSeconds(3600));
        when(userRepository.findByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull("user_1f9a74af"))
                .thenReturn(List.of(active));

        authService.resetPassword(new PasswordResetRequest("test@example.com", "new-password123", "vtoken"));

        assertThat(passwordEncoder.matches("new-password123", user.getPasswordHash())).isTrue();
        assertThat(active.getRevokedAt()).isNotNull();
    }

    @Test
    void resetPassword_unknownEmail_throwsInvalidVerificationToken() {
        // 토큰은 소비됐지만 계정이 없을 때 계정 존재 여부를 노출하지 않도록 토큰 오류로 처리한다.
        when(userRepository.findByEmailAndProvider("nobody@example.com", User.PROVIDER_LOCAL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetRequest("nobody@example.com", "new-password123", "vtoken")))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void resetPassword_oauthOnlyEmail_throwsPasswordLoginUnavailable() {
        // 인증코드를 통과한 요청자는 이미 메일함을 통제하므로 가입 provider를 알려준다.
        when(userRepository.findByEmailAndProvider("oauth@example.com", User.PROVIDER_LOCAL))
                .thenReturn(Optional.empty());
        when(userRepository.findAllByEmail("oauth@example.com"))
                .thenReturn(List.of(new User("user_google1", "oauth@example.com", "google", "구글 사용자", null)));

        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetRequest("oauth@example.com", "new-password123", "vtoken")))
                .isInstanceOf(PasswordLoginUnavailableException.class)
                .hasMessageContaining("google");
    }

    @Test
    void resetPassword_multipleOauthAccounts_listsEveryProvider() {
        // 하나만 고르면 사용자가 실제로 쓰는 수단을 못 짚을 수 있다.
        when(userRepository.findByEmailAndProvider("oauth@example.com", User.PROVIDER_LOCAL))
                .thenReturn(Optional.empty());
        when(userRepository.findAllByEmail("oauth@example.com")).thenReturn(List.of(
                new User("user_naver1", "oauth@example.com", "naver", "네이버 사용자", null),
                new User("user_google1", "oauth@example.com", "google", "구글 사용자", null)));

        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetRequest("oauth@example.com", "new-password123", "vtoken")))
                .isInstanceOf(PasswordLoginUnavailableException.class)
                .hasMessageContaining("google, naver");
    }

    @Test
    void resetPassword_invalidToken_propagatesTokenError() {
        doThrow(new InvalidVerificationTokenException())
                .when(emailVerificationService).consumeForPasswordReset("test@example.com", "bad-token");

        assertThatThrownBy(() -> authService.resetPassword(
                new PasswordResetRequest("test@example.com", "new-password123", "bad-token")))
                .isInstanceOf(InvalidVerificationTokenException.class);
    }

    @Test
    void refresh_expiredToken_throwsInvalidRefreshToken() {
        UserRefreshToken expired = new UserRefreshToken("user_1f9a74af", "any-hash", Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("expired-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_unknownToken_throwsInvalidRefreshToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logout_validToken_revokesToken() {
        UserRefreshToken existing = new UserRefreshToken("user_1f9a74af", "any-hash", Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(existing));

        authService.logout(new RefreshRequest("some-refresh-token"));

        assertThat(existing.getRevokedAt()).isNotNull();
    }

    @Test
    void logout_unknownToken_isIdempotent() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        authService.logout(new RefreshRequest("unknown-token"));
    }

    @Test
    void exchangeOAuthCode_validCode_issuesTokens() {
        String code = oAuthExchangeCodeStore.issue("user_1f9a74af");
        when(userRepository.findById("user_1f9a74af")).thenReturn(Optional.of(newUser("password123")));

        LoginResponse response = authService.exchangeOAuthCode(new OAuthExchangeRequest(code));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void exchangeOAuthCode_unknownCode_throwsInvalidOAuthCode() {
        assertThatThrownBy(() -> authService.exchangeOAuthCode(new OAuthExchangeRequest("unknown-code")))
                .isInstanceOf(InvalidOAuthCodeException.class);
    }

    @Test
    void exchangeOAuthCode_alreadyConsumedCode_throwsInvalidOAuthCode() {
        String code = oAuthExchangeCodeStore.issue("user_1f9a74af");
        when(userRepository.findById("user_1f9a74af")).thenReturn(Optional.of(newUser("password123")));
        authService.exchangeOAuthCode(new OAuthExchangeRequest(code));

        assertThatThrownBy(() -> authService.exchangeOAuthCode(new OAuthExchangeRequest(code)))
                .isInstanceOf(InvalidOAuthCodeException.class);
    }

    @Test
    void updateDisplayName_trimsAndReturnsUpdatedProfile() {
        User user = new User("user_1", "user@example.com", User.PROVIDER_LOCAL, "옛 이름", "hash");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));

        MeResponse response = authService.updateDisplayName("user_1", new DisplayNameUpdateRequest("  새 이름  "));

        assertThat(response.displayName()).isEqualTo("새 이름");
        assertThat(user.getDisplayName()).isEqualTo("새 이름");
        assertThat(response.email()).isEqualTo("user@example.com");
    }

    @Test
    void updateDisplayName_unknownUserThrows() {
        when(userRepository.findById("user_none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.updateDisplayName("user_none", new DisplayNameUpdateRequest("이름")))
                .isInstanceOf(UserNotFoundException.class);
    }

    private User localUser(String rawPassword) {
        return new User("user_1", "user@example.com", User.PROVIDER_LOCAL, "이름", passwordEncoder.encode(rawPassword));
    }

    @Test
    void changePassword_replacesHashAndKeepsCurrentSession() {
        User user = localUser("oldPassword1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        // 현재 세션 토큰과 다른 기기 토큰이 하나씩 살아 있는 상태.
        UserRefreshToken current = new UserRefreshToken("user_1", sha256("current-refresh"), Instant.now().plusSeconds(3600));
        UserRefreshToken other = new UserRefreshToken("user_1", sha256("other-refresh"), Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull("user_1")).thenReturn(List.of(current, other));

        authService.changePassword("user_1",
                new PasswordChangeRequest("oldPassword1", "newPassword1"), "current-refresh");

        assertThat(passwordEncoder.matches("newPassword1", user.getPasswordHash())).isTrue();
        assertThat(current.getRevokedAt()).isNull();
        assertThat(other.getRevokedAt()).isNotNull();
    }

    @Test
    void changePassword_withoutRefreshCookie_revokesEverySession() {
        User user = localUser("oldPassword1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        UserRefreshToken only = new UserRefreshToken("user_1", sha256("some-refresh"), Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull("user_1")).thenReturn(List.of(only));

        authService.changePassword("user_1", new PasswordChangeRequest("oldPassword1", "newPassword1"), null);

        assertThat(only.getRevokedAt()).isNotNull();
    }

    @Test
    void changePassword_wrongCurrentPasswordThrows() {
        User user = localUser("oldPassword1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.changePassword("user_1",
                new PasswordChangeRequest("wrongPassword", "newPassword1"), "current-refresh"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(passwordEncoder.matches("oldPassword1", user.getPasswordHash())).isTrue();
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void changePassword_oauthOnlyAccountThrows() {
        User user = new User("user_1", "user@example.com", "google", "이름", null);
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.changePassword("user_1",
                new PasswordChangeRequest("anything", "newPassword1"), null))
                .isInstanceOf(PasswordLoginUnavailableException.class);
        verifyNoInteractions(refreshTokenRepository);
    }

    /** AuthService가 refresh token을 저장할 때 쓰는 해시와 같아야 현재 세션을 짚어낼 수 있다. */
    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void changeEmail_consumesTokenAndKeepsCurrentSession() {
        User user = localUser("password1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndProvider("new@example.com", User.PROVIDER_LOCAL)).thenReturn(false);
        UserRefreshToken current = new UserRefreshToken("user_1", sha256("current-refresh"), Instant.now().plusSeconds(3600));
        UserRefreshToken other = new UserRefreshToken("user_1", sha256("other-refresh"), Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull("user_1")).thenReturn(List.of(current, other));

        MeResponse response = authService.changeEmail("user_1",
                new EmailChangeRequest("  New@Example.COM  ", "verification-token"), "current-refresh");

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        // 새 주소로 받은 토큰이어야 한다.
        verify(emailVerificationService).consumeForEmailChange("new@example.com", "verification-token");
        assertThat(current.getRevokedAt()).isNull();
        assertThat(other.getRevokedAt()).isNotNull();
    }

    @Test
    void changeEmail_duplicateOnSameProviderThrows() {
        User user = localUser("password1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndProvider("taken@example.com", User.PROVIDER_LOCAL)).thenReturn(true);

        assertThatThrownBy(() -> authService.changeEmail("user_1",
                new EmailChangeRequest("taken@example.com", "verification-token"), null))
                .isInstanceOf(DuplicateEmailException.class);
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        verifyNoInteractions(refreshTokenRepository);
    }

    /** 같은 이메일이 다른 provider에 있는 건 막지 않는다 — 계정은 (email, provider)로 유일하다. */
    @Test
    void changeEmail_sameEmailOnOtherProviderIsAllowed() {
        User user = new User("user_1", "user@example.com", "google", "이름", null);
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailAndProvider("shared@example.com", "google")).thenReturn(false);
        when(refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull("user_1")).thenReturn(List.of());

        authService.changeEmail("user_1",
                new EmailChangeRequest("shared@example.com", "verification-token"), null);

        assertThat(user.getEmail()).isEqualTo("shared@example.com");
    }

    /** 토큰이 유효하지 않으면 이메일을 건드리지 않는다. */
    @Test
    void changeEmail_invalidTokenLeavesEmailUnchanged() {
        User user = localUser("password1");
        when(userRepository.findById("user_1")).thenReturn(Optional.of(user));
        doThrow(new InvalidVerificationTokenException())
                .when(emailVerificationService).consumeForEmailChange("new@example.com", "bad-token");

        assertThatThrownBy(() -> authService.changeEmail("user_1",
                new EmailChangeRequest("new@example.com", "bad-token"), null))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(user.getEmail()).isEqualTo("user@example.com");
        verifyNoInteractions(refreshTokenRepository);
    }
}
