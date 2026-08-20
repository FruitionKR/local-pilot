package fruition.access.user.service;

import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.RefreshRequest;
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
import static org.mockito.Mockito.when;

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
}
