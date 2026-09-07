package fruition.access.user.service;

import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfaChallenge;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.MfaLoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.DisplayNameUpdateRequest;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.dto.PasswordChangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.dto.SessionListResponse;
import fruition.access.user.dto.SessionResponse;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidMfaChallengeException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.exception.SessionNotFoundException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.mfa.MfaService;
import fruition.access.user.repository.UserMfaChallengeRepository;
import fruition.access.user.repository.UserRefreshTokenRepository;
import fruition.access.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthExchangeCodeStore oAuthExchangeCodeStore;
    private final EmailVerificationService emailVerificationService;
    private final MfaService mfaService;
    private final UserMfaChallengeRepository mfaChallengeRepository;
    private final long refreshTokenExpirationSeconds;
    private final long mfaChallengeTtlSeconds;

    public AuthService(UserRepository userRepository,
                       UserRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       OAuthExchangeCodeStore oAuthExchangeCodeStore,
                       EmailVerificationService emailVerificationService,
                       MfaService mfaService,
                       UserMfaChallengeRepository mfaChallengeRepository,
                       @Value("${app.jwt.refresh-token-expiration-seconds}") long refreshTokenExpirationSeconds,
                       @Value("${app.auth.mfa.challenge-ttl-seconds:300}") long mfaChallengeTtlSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.oAuthExchangeCodeStore = oAuthExchangeCodeStore;
        this.emailVerificationService = emailVerificationService;
        this.mfaService = mfaService;
        this.mfaChallengeRepository = mfaChallengeRepository;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.mfaChallengeTtlSeconds = mfaChallengeTtlSeconds;
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

        if (mfaService.isEnabled(user.getId())) {
            log.info("[로그인 1단계 통과] userId={} mfa=required", user.getId());
            return LoginResponse.mfaRequired(issueMfaChallenge(user));
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
        refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .ifPresent(UserRefreshToken::revoke);
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
        if (mfaService.isEnabled(user.getId())) {
            return LoginResponse.mfaRequired(issueMfaChallenge(user));
        }

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
    public MeResponse updateDisplayName(String userId, DisplayNameUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.changeDisplayName(request.displayName().trim());
        log.info("[표시 이름 변경] userId={}", userId);
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    /**
     * 로그인 상태에서 비밀번호를 바꾼다.
     *
     * <p>{@code currentRefreshToken}은 요청에 실려 온 refresh 쿠키다. 이 세션만 남기고
     * 나머지를 폐기해, 비밀번호가 샜을 때 다른 기기의 세션을 끊으면서도 방금 인증을 마친
     * 사용자를 로그아웃시키지 않는다. 쿠키가 없으면 전부 폐기한다.
     */
    @Transactional
    public void changePassword(String userId, PasswordChangeRequest request, String currentRefreshToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // OAuth 전용 계정은 password_hash가 없어 "현재 비밀번호"라는 개념이 성립하지 않는다.
        if (user.getPasswordHash() == null) {
            log.warn("[비밀번호 변경 거부] reason=no_password userId={} provider={}", userId, user.getProvider());
            throw new PasswordLoginUnavailableException(List.of(user.getProvider()));
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("[비밀번호 변경 거부] reason=current_password_mismatch userId={}", userId);
            throw new InvalidCredentialsException();
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        int revoked = revokeOtherSessions(userId, currentRefreshToken);
        log.info("[비밀번호 변경 성공] userId={} revokedSessions={}", userId, revoked);
    }

    /**
     * 로그인 상태에서 이메일을 바꾼다.
     *
     * <p>새 주소로 받은 인증번호 토큰으로 그 메일함을 통제하는지 확인한다. 계정은
     * {@code (email, provider)}로 유일해야 하므로 같은 provider의 기존 계정과 부딪히면 거절한다.
     * OAuth 로그인은 {@code (provider, provider_user_id)}로 사용자를 찾으므로 이메일이 바뀌어도 끊기지 않는다.
     */
    @Transactional
    public MeResponse changeEmail(String userId, EmailChangeRequest request, String currentRefreshToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String newEmail = request.newEmail().trim().toLowerCase();

        emailVerificationService.consumeForEmailChange(newEmail, request.verificationToken());

        if (!newEmail.equals(user.getEmail())
                && userRepository.existsByEmailAndProvider(newEmail, user.getProvider())) {
            log.warn("[이메일 변경 거부] reason=duplicate_email userId={} provider={}", userId, user.getProvider());
            throw new DuplicateEmailException(newEmail);
        }

        user.changeEmail(newEmail);
        // 사전 조회 이후 발생한 중복도 커밋 전에 확인해 같은 오류 계약으로 반환한다.
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_users_email_provider".equals(violation.getConstraintName())) {
                    throw new DuplicateEmailException(newEmail);
                }
            }
            throw exception;
        }
        int revoked = revokeOtherSessions(userId, currentRefreshToken);
        log.info("[이메일 변경 성공] userId={} revokedSessions={}", userId, revoked);
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    /**
     * 현재 세션만 남기고 나머지 refresh token을 폐기한다.
     * {@code currentRefreshToken}이 없으면 지킬 세션을 특정할 수 없어 전부 폐기한다.
     */
    private int revokeOtherSessions(String userId, String currentRefreshToken) {
        String keepTokenHash = currentRefreshToken == null ? null : sha256(currentRefreshToken);
        int revoked = 0;
        for (UserRefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            if (token.getTokenHash().equals(keepTokenHash)) {
                continue;
            }
            token.revoke();
            revoked++;
        }
        return revoked;
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
            // 같은 이메일에 여러 provider 계정이 있을 수 있다. 하나만 고르면 사용자가 실제로
            // 쓰는 수단을 못 짚을 수 있으므로 전부 알린다.
            List<String> providers = userRepository.findAllByEmail(email).stream()
                    .map(User::getProvider)
                    .distinct()
                    .sorted()
                    .toList();
            if (providers.isEmpty()) {
                throw new InvalidVerificationTokenException();
            }
            throw new PasswordLoginUnavailableException(providers);
        }
        User user = localAccount.get();

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        // 비밀번호 변경 시 기존 세션(refresh token)을 모두 폐기한다.
        for (UserRefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())) {
            token.revoke();
        }
        log.info("[비밀번호 재설정 성공] userId={} email={}", user.getId(), user.getEmail());
    }

    /** 폐기되지 않은 세션 목록. 지금 요청을 보낸 세션에는 {@code current} 표시를 단다. */
    public SessionListResponse sessions(String userId, String currentRefreshToken) {
        String currentHash = currentRefreshToken == null ? null : sha256(currentRefreshToken);
        return new SessionListResponse(
                refreshTokenRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                        .map(token -> new SessionResponse(
                                token.getId(),
                                token.getUserAgent(),
                                token.getTokenHash().equals(currentHash),
                                token.getCreatedAt(),
                                token.getExpiresAt()))
                        .toList());
    }

    /** 특정 기기 로그아웃. 현재 세션을 지정하면 스스로 로그아웃하는 것이라 허용한다. */
    @Transactional
    public void revokeSession(String userId, Long sessionId) {
        UserRefreshToken token = refreshTokenRepository.findById(sessionId)
                // 남의 세션도 존재를 드러내지 않도록 404로 통일한다.
                .filter(found -> found.getUserId().equals(userId))
                .filter(found -> found.getRevokedAt() == null)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        token.revoke();
        log.info("[세션 폐기] userId={} sessionId={}", userId, sessionId);
    }

    /**
     * 요청의 User-Agent. 발급 경로가 login·refresh·OAuth 교환 셋이라 시그니처를 모두 바꾸는 대신
     * 요청 컨텍스트에서 읽는다({@code BaseExceptionHandler}가 요청 정보를 읽는 방식과 같다).
     * 요청 밖(테스트·비동기)에서는 null이다.
     */
    private String currentUserAgent() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        String userAgent = servletAttributes.getRequest().getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }

    /** 로그인 2단계. 코드가 맞아야 토큰을 준다. */
    @Transactional
    public LoginResponse loginMfa(MfaLoginRequest request) {
        UserMfaChallenge challenge = mfaChallengeRepository.findByTokenHash(sha256(request.mfaToken()))
                .filter(UserMfaChallenge::isUsable)
                .orElseThrow(InvalidMfaChallengeException::new);
        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new UserNotFoundException(challenge.getUserId()));

        // 코드가 틀리면 challenge를 소비하지 않는다 — 오타 한 번에 로그인을 처음부터 다시 하게 만들지 않는다.
        mfaService.verify(user.getId(), request.code());
        challenge.consume();

        LoginResponse response = issueTokenPair(user);
        log.info("[로그인 성공] userId={} email={} mfa=verified", user.getId(), user.getEmail());
        return response;
    }

    private String issueMfaChallenge(User user) {
        String token = generateOpaqueToken();
        mfaChallengeRepository.save(new UserMfaChallenge(
                "mfc_" + UUID.randomUUID().toString().replace("-", ""),
                user.getId(),
                sha256(token),
                Instant.now().plusSeconds(mfaChallengeTtlSeconds)));
        return token;
    }

    private LoginResponse issueTokenPair(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());

        String refreshTokenValue = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(refreshTokenExpirationSeconds);
        refreshTokenRepository.save(new UserRefreshToken(
                user.getId(), sha256(refreshTokenValue), expiresAt, currentUserAgent()));

        return LoginResponse.tokens(
                accessToken, refreshTokenValue, jwtTokenProvider.getAccessTokenExpirationSeconds());
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
