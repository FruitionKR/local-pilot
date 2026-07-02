package fruition.user.service;

import fruition.user.dto.SignupRequest;
import fruition.user.dto.SignupResponse;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    UserService userService;

    @Test
    void signup_newEmail_createsUserWithHashedPassword() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.displayName()).isEqualTo("tes");
        assertThat(response.id()).startsWith("user_");
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(new SignupRequest("test@example.com", "password123")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void signup_displayName_isFirstThreeCharsOfEmail() {
        userService = new UserService(userRepository, passwordEncoder);
        when(userRepository.existsByEmail("jane.doe@example.com")).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123"));

        assertThat(response.displayName()).isEqualTo("jan");
    }
}
