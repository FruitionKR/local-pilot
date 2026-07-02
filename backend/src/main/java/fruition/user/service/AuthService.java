package fruition.user.service;

import fruition.security.JwtTokenProvider;
import fruition.user.domain.User;
import fruition.user.domain.UserRefreshToken;
import fruition.user.dto.LoginRequest;
import fruition.user.dto.LoginResponse;
import fruition.user.dto.MeResponse;
import fruition.user.dto.RefreshRequest;
import fruition.user.exception.InvalidCredentialsException;
import fruition.user.exception.InvalidRefreshTokenException;
import fruition.user.exception.UserNotFoundException;
import fruition.user.repository.UserRefreshTokenRepository;
import fruition.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final long refreshTokenExpirationSeconds;

    public AuthService(UserRepository userRepository,
                       UserRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       @Value("${app.jwt.refresh-token-expiration-seconds}") long refreshTokenExpirationSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueTokenPair(user);
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest request) {
        UserRefreshToken tokenRow = refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!tokenRow.isValid()) {
            throw new InvalidRefreshTokenException();
        }
        tokenRow.revoke();

        User user = userRepository.findById(tokenRow.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        return issueTokenPair(user);
    }

    @Transactional
    public void logout(RefreshRequest request) {
        UserRefreshToken tokenRow = refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .orElseThrow(InvalidRefreshTokenException::new);
        tokenRow.revoke();
    }

    public MeResponse me(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    private LoginResponse issueTokenPair(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

        String refreshTokenValue = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(refreshTokenExpirationSeconds);
        refreshTokenRepository.save(new UserRefreshToken(user.getId(), sha256(refreshTokenValue), expiresAt));

        return new LoginResponse(accessToken, refreshTokenValue, "Bearer", jwtTokenProvider.getAccessTokenExpirationSeconds());
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
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
