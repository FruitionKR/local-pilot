package fruition.user.service;

import fruition.security.JwtTokenProvider;
import fruition.security.oauth.OAuthExchangeCodeStore;
import fruition.user.domain.User;
import fruition.user.domain.UserRefreshToken;
import fruition.user.dto.LoginRequest;
import fruition.user.dto.LoginResponse;
import fruition.user.dto.MeResponse;
import fruition.user.dto.OAuthExchangeRequest;
import fruition.user.dto.RefreshRequest;
import fruition.user.exception.InvalidCredentialsException;
import fruition.user.exception.InvalidOAuthCodeException;
import fruition.user.exception.InvalidRefreshTokenException;
import fruition.user.exception.UserNotFoundException;
import fruition.user.repository.UserRefreshTokenRepository;
import fruition.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthExchangeCodeStore oAuthExchangeCodeStore;
    private final long refreshTokenExpirationSeconds;

    public AuthService(UserRepository userRepository,
                       UserRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       OAuthExchangeCodeStore oAuthExchangeCodeStore,
                       @Value("${app.jwt.refresh-token-expiration-seconds}") long refreshTokenExpirationSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.oAuthExchangeCodeStore = oAuthExchangeCodeStore;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        log.info("[로그인 요청] email={}", email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[로그인 실패] reason=unknown_email email={}", email);
                    return new InvalidCredentialsException();
                });

        if (user.getPasswordHash() == null) {
            log.warn("[로그인 실패] reason=password_login_unavailable userId={} email={}", user.getId(), email);
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("[로그인 실패] reason=password_mismatch userId={} email={}", user.getId(), email);
            throw new InvalidCredentialsException();
        }

        LoginResponse response = issueTokenPair(user);
        log.info("[로그인 성공] userId={} email={}", user.getId(), user.getEmail());
        return response;
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

    @Transactional
    public LoginResponse exchangeOAuthCode(OAuthExchangeRequest request) {
        log.info("[OAuth code 교환 요청]");
        String userId = oAuthExchangeCodeStore.consume(request.code())
                .orElseThrow(() -> {
                    log.warn("[OAuth code 교환 실패] reason=invalid_code");
                    return new InvalidOAuthCodeException();
                });
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("[OAuth code 교환 실패] reason=user_not_found userId={}", userId);
                    return new InvalidOAuthCodeException();
                });
        LoginResponse response = issueTokenPair(user);
        log.info("[OAuth code 교환 성공] userId={} email={}", user.getId(), user.getEmail());
        return response;
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
