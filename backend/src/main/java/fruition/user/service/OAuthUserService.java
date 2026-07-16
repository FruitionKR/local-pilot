package fruition.user.service;

import fruition.security.oauth.domain.OAuth2UserInfo;
import fruition.user.domain.User;
import fruition.user.domain.UserOAuthAccount;
import fruition.user.exception.OAuthEmailNotProvidedException;
import fruition.user.repository.UserOAuthAccountRepository;
import fruition.user.repository.UserRepository;
import fruition.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OAuthUserService {

    private static final Logger log = LoggerFactory.getLogger(OAuthUserService.class);

    private final UserRepository userRepository;
    private final UserOAuthAccountRepository oauthAccountRepository;
    private final WorkspaceService workspaceService;

    public OAuthUserService(UserRepository userRepository,
                            UserOAuthAccountRepository oauthAccountRepository,
                            WorkspaceService workspaceService) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public User findOrCreateUser(String provider, OAuth2UserInfo userInfo) {
        String providerUserId = userInfo.getProviderUserId();
        log.info("[OAuth 로그인 요청] provider={} providerUserId={}", provider, providerUserId);

        var existingLink = oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingLink.isPresent()) {
            User user = userRepository.findById(existingLink.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "연결된 사용자를 찾을 수 없습니다: userId=" + existingLink.get().getUserId()));
            log.info("[OAuth 로그인 성공] provider={} userId={} link=existing_provider", provider, user.getId());
            return user;
        }

        String email = userInfo.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("[OAuth 로그인 실패] provider={} reason=email_not_provided", provider);
            throw new OAuthEmailNotProvidedException(provider);
        }
        String normalizedEmail = email.trim().toLowerCase();

        var existingUser = userRepository.findByEmail(normalizedEmail);
        User user = existingUser.orElseGet(() -> createUser(provider, normalizedEmail, userInfo.getName()));

        oauthAccountRepository.save(new UserOAuthAccount(user.getId(), provider, providerUserId));
        log.info("[OAuth 계정 연결] provider={} userId={} email={} link={}",
                provider,
                user.getId(),
                user.getEmail(),
                existingUser.isPresent() ? "existing_email" : "new_user");
        return user;
    }

    private User createUser(String provider, String email, String name) {
        String displayName = name != null && !name.isBlank()
                ? name.trim()
                : email.substring(0, Math.min(3, email.length()));
        String displayNameSource = name != null && !name.isBlank() ? "provider" : "email_prefix";
        String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
        User user = new User(userId, email, displayName, null);
        userRepository.save(user);
        workspaceService.createDefault(user.getId(), user.getDisplayName());
        log.info("[OAuth 신규 사용자 생성] provider={} userId={} email={} displayNameSource={}",
                provider,
                user.getId(),
                user.getEmail(),
                displayNameSource);
        return user;
    }
}
