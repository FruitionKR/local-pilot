package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.dto.UserSettingsResponse;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSettingsService {

    private final UserRepository userRepository;

    public UserSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserSettingsResponse get(String userId) {
        return new UserSettingsResponse(findUser(userId).isWebSearchEnabled());
    }

    @Transactional
    public UserSettingsResponse update(String userId, boolean webSearchEnabled) {
        User user = findUser(userId);
        user.changeWebSearchEnabled(webSearchEnabled);
        return new UserSettingsResponse(user.isWebSearchEnabled());
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
