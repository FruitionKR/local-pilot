package fruition.user.service;

import fruition.security.oauth.GoogleOAuth2UserInfo;
import fruition.user.domain.User;
import fruition.user.domain.UserOAuthAccount;
import fruition.user.exception.OAuthEmailNotProvidedException;
import fruition.user.repository.UserOAuthAccountRepository;
import fruition.user.repository.UserRepository;
import fruition.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserOAuthAccountRepository oauthAccountRepository;
    @Mock WorkspaceService workspaceService;

    OAuthUserService oAuthUserService;

    @BeforeEach
    void setUp() {
        oAuthUserService = new OAuthUserService(userRepository, oauthAccountRepository, workspaceService);
    }

    private GoogleOAuth2UserInfo googleUserInfo(String sub, String email, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", email);
        attributes.put("name", name);
        return new GoogleOAuth2UserInfo(attributes);
    }

    @Test
    void findOrCreateUser_existingLink_returnsLinkedUser() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.of(new UserOAuthAccount("user_1f9a74af", "google", "google-sub-1")));
        when(userRepository.findById("user_1f9a74af"))
                .thenReturn(Optional.of(new User("user_1f9a74af", "test@example.com", "tes", null)));

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "test@example.com", "Tester"));

        assertThat(user.getId()).isEqualTo("user_1f9a74af");
        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreateUser_existingEmailNoLink_linksAccountWithoutCreatingNewUser() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("test@example.com"))
                .thenReturn(Optional.of(new User("user_existing1", "test@example.com", "tes", "hash")));

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "test@example.com", "Tester"));

        assertThat(user.getId()).isEqualTo("user_existing1");
        verify(userRepository, never()).save(any());
        verify(oauthAccountRepository).save(any());
    }

    @Test
    void findOrCreateUser_newEmail_createsUserAndDefaultWorkspace() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "new@example.com", "New User"));

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPasswordHash()).isNull();
        verify(userRepository).save(any());
        verify(workspaceService).createDefault(user.getId(), user.getDisplayName());
        verify(oauthAccountRepository).save(any());
    }

    @Test
    void findOrCreateUser_noEmailProvided_throwsException() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", null, "No Email")))
                .isInstanceOf(OAuthEmailNotProvidedException.class);
    }
}
