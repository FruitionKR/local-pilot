package fruition.access.user.service;

import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserRefreshTokenRepository;
import fruition.access.user.repository.UserRepository;
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
    private final EmailVerificationService emailVerificationService;
    private final long refreshTokenExpirationSeconds;

    public AuthService(UserRepository userRepository,
                       UserRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       OAuthExchangeCodeStore oAuthExchangeCodeStore,
                       EmailVerificationService emailVerificationService,
                       @Value("${app.jwt.refresh-token-expiration-seconds}") long refreshTokenExpirationSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.oAuthExchangeCodeStore = oAuthExchangeCodeStore;
        this.emailVerificationService = emailVerificationService;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        log.info("[로그인 요청] email={}", email);

        User user = userRepository.findByEmailAndProvider(email, User.PROVIDER_LOCAL)
                .orElseThrow(() -> {
                    log.warn("[로그인 실패] reason=unknown_email email={}", email);
                    return new InvalidCredentialsException();
                });

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

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = request.email().trim().toLowerCase();
        emailVerificationService.consumeForPasswordReset(email, request.verificationToken());

        // 인증코드를 통과했으므로 이 시점의 요청자는 해당 메일함을 통제한다. 따라서 OAuth로만
        // 가입된 이메일에는 provider를 알려줘도 열거 위험이 없고, 토큰 오류로 뭉개는 편보다 낫다.
        // 반면 아무 계정도 없는 경우는 계정 존재 여부를 노출하지 않도록 토큰 오류로 유지한다.
        var localAccount = userRepository.findByEmailAndProvider(email, User.PROVIDER_LOCAL);
        if (localAccount.isEmpty()) {
            var sameEmailAccounts = userRepository.findAllByEmail(email);
            if (sameEmailAccounts.isEmpty()) {
                throw new InvalidVerificationTokenException();
            }
            throw new PasswordLoginUnavailableException(sameEmailAccounts.get(0).getProvider());
        }
        User user = localAccount.get();

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        // 비밀번호 변경 시 기존 세션(refresh token)을 모두 폐기한다.
        for (UserRefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())) {
            token.revoke();
        }
        log.info("[비밀번호 재설정 성공] userId={} email={}", user.getId(), user.getEmail());
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
