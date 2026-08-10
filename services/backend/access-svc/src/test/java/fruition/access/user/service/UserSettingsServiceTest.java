package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserSettingsServiceTest {

    @Test
    void updatesGlobalWebSearchSetting() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User("user-1", "user@example.com", "사용자", "hash");
        when(repository.findById("user-1")).thenReturn(Optional.of(user));

        UserSettingsService service = new UserSettingsService(repository);

        assertThat(service.get("user-1").webSearchEnabled()).isFalse();
        assertThat(service.update("user-1", true).webSearchEnabled()).isTrue();
        assertThat(service.get("user-1").webSearchEnabled()).isTrue();
    }
}
