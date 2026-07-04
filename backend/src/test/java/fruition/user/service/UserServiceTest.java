package fruition.user.service;

import fruition.user.dto.SignupRequest;
import fruition.user.dto.SignupResponse;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.repository.UserRepository;
import fruition.workspace.service.WorkspaceService;
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

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, workspaceService);
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
}
