package fruition.user.service;

import fruition.security.oauth.domain.OAuth2UserInfo;
import fruition.user.domain.User;
import fruition.user.domain.UserOAuthAccount;
import fruition.user.exception.OAuthEmailNotProvidedException;
import fruition.user.repository.UserOAuthAccountRepository;
import fruition.user.repository.UserRepository;
import fruition.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OAuthUserService {

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

        var existingLink = oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingLink.isPresent()) {
            return userRepository.findById(existingLink.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "연결된 사용자를 찾을 수 없습니다: userId=" + existingLink.get().getUserId()));
        }

        String email = userInfo.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuthEmailNotProvidedException(provider);
        }
        String normalizedEmail = email.trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail).orElseGet(() -> createUser(normalizedEmail));

        oauthAccountRepository.save(new UserOAuthAccount(user.getId(), provider, providerUserId));
        return user;
    }

    private User createUser(String email) {
        String displayName = email.substring(0, Math.min(3, email.length()));
        String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
        User user = new User(userId, email, displayName, null);
        userRepository.save(user);
        workspaceService.createDefault(user.getId(), user.getDisplayName());
        return user;
    }
}
