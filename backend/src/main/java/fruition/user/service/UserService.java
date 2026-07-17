package fruition.user.service;

import fruition.user.domain.User;
import fruition.user.dto.SignupRequest;
import fruition.user.dto.SignupResponse;
import fruition.user.exception.DuplicateEmailException;
import fruition.user.repository.UserRepository;
import fruition.util.DisplayNames;
import fruition.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

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
        log.info("[회원가입 요청] email={}", email);

        if (userRepository.existsByEmail(email)) {
            log.warn("[회원가입 실패] reason=duplicate_email email={}", email);
            throw new DuplicateEmailException(email);
        }

        String displayName = DisplayNames.resolve(request.displayName(), email);
        String displayNameSource = DisplayNames.isPresent(request.displayName()) ? "request" : "email_prefix";

        String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
        User user = new User(userId, email, displayName, passwordEncoder.encode(request.password()));
        userRepository.save(user);

        workspaceService.createDefault(user.getId(), user.getDisplayName());
        log.info("[회원가입 성공] userId={} email={} displayNameSource={}", user.getId(), user.getEmail(), displayNameSource);

        return new SignupResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
