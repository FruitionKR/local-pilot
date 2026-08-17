package fruition.access.user.service;

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
    void checkEmailAvailability_existingOAuthEmail_returnsFalse() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThat(userService.checkEmailAvailability(new EmailAvailabilityRequest(" TEST@example.com ")).available())
                .isFalse();
    }

    @Test
    void checkEmailAvailability_newEmail_returnsTrue() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        assertThat(userService.checkEmailAvailability(new EmailAvailabilityRequest("new@example.com")).available())
                .isTrue();
    }

    @Test
    void signup_newEmail_createsUserWithHashedPassword() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.displayName()).isEqualTo("tes");
        assertThat(response.id()).startsWith("user_");
    }

    @Test
    void signup_newEmail_createsDefaultWorkspace() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        verify(workspaceService).createDefault(response.id(), "tes");
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(new SignupRequest("test@example.com", "password123")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void signup_displayName_isFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmail("jane.doe@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123"));

        assertThat(response.displayName()).isEqualTo("jan");
    }

    @Test
    void signup_displayNameProvided_usesTrimmedDisplayName() {
        when(userRepository.existsByEmail("jane.doe@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  제인  ", "vtoken"));

        assertThat(response.displayName()).isEqualTo("제인");
    }

    @Test
    void signup_blankDisplayName_usesFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmail("jane.doe@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  ", "vtoken"));

        assertThat(response.displayName()).isEqualTo("jan");
    }
}
