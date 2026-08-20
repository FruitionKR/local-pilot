package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailAvailabilityRequest;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.SignupResponse;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock WorkspaceService workspaceService;
    @Mock EmailVerificationService emailVerificationService;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, workspaceService, emailVerificationService);
    }

    @Test
    void checkEmailAvailability_existingLocalEmail_returnsFalse() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(true);

        assertThat(userService.checkEmailAvailability(new EmailAvailabilityRequest(" TEST@example.com ")).available())
                .isFalse();
    }

    @Test
    void checkEmailAvailability_noLocalAccount_returnsTrue() {
        // local 계정이 없으면 가입 가능하다. OAuth 계정만 있는 이메일도 여기에 해당한다.
        // 조회가 local로 한정되는 것이 그 동작의 근거이므로 provider 인자까지 검증한다.
        when(userRepository.existsByEmailAndProvider("oauth@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        assertThat(userService.checkEmailAvailability(new EmailAvailabilityRequest("oauth@example.com")).available())
                .isTrue();
        verify(userRepository).existsByEmailAndProvider("oauth@example.com", User.PROVIDER_LOCAL);
    }

    @Test
    void signup_newEmail_createsUserWithHashedPassword() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.displayName()).isEqualTo("tes");
        assertThat(response.id()).startsWith("user_");
    }

    @Test
    void signup_newEmail_createsDefaultWorkspace() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        verify(workspaceService).createDefault(response.id(), "tes");
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(new SignupRequest("test@example.com", "password123")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void signup_displayName_isFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123"));

        assertThat(response.displayName()).isEqualTo("jan");
    }

    @Test
    void signup_displayNameProvided_usesTrimmedDisplayName() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  제인  ", "vtoken"));

        assertThat(response.displayName()).isEqualTo("제인");
    }

    @Test
    void signup_blankDisplayName_usesFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  ", "vtoken"));

        assertThat(response.displayName()).isEqualTo("jan");
    }
}
