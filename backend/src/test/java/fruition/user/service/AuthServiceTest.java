package fruition.user.service;

import fruition.security.JwtTokenProvider;
import fruition.user.domain.User;
import fruition.user.domain.UserRefreshToken;
import fruition.user.dto.LoginRequest;
import fruition.user.dto.LoginResponse;
import fruition.user.dto.RefreshRequest;
import fruition.user.exception.InvalidCredentialsException;
import fruition.user.exception.InvalidRefreshTokenException;
import fruition.user.repository.UserRefreshTokenRepository;
import fruition.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserRefreshTokenRepository refreshTokenRepository;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            "test-only-jwt-secret-32-bytes-minimum-length", 900);
    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider, 1209600);
    }

    private User newUser(String rawPassword) {
        return new User("user_1f9a74af", "test@example.com", "tes", passwordEncoder.encode(rawPassword));
    }

    @Test
    void login_correctPassword_issuesAccessAndRefreshToken() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(newUser("password123")));

        LoginResponse response = authService.login(new LoginRequest("test@example.com", "password123"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(newUser("password123")));

        assertThatThrownBy(() -> authService.login(new LoginRequest("test@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

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
    void logout_unknownToken_throwsInvalidRefreshToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.logout(new RefreshRequest("unknown-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
