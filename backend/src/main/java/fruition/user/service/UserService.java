package fruition.user.service;

import fruition.user.domain.User;
import fruition.user.dto.SignupRequest;
import fruition.user.dto.SignupResponse;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.repository.UserRepository;
import fruition.workspace.service.WorkspaceService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceService workspaceService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, WorkspaceService workspaceService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        String displayName = email.substring(0, Math.min(3, email.length()));

        String userId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = new User(userId, email, displayName, passwordEncoder.encode(request.password()));
        userRepository.save(user);

        workspaceService.createDefault(user.getId(), user.getDisplayName());

        return new SignupResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
