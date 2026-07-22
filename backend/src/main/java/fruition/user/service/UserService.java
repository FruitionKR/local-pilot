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
    private final EmailVerificationService emailVerificationService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       WorkspaceService workspaceService, EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.workspaceService = workspaceService;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase();
        log.info("[회원가입 요청] email={}", email);

        // 유효 토큰 낭비를 막기 위해 중복 검사를 토큰 소비보다 먼저 수행한다.
        if (userRepository.existsByEmail(email)) {
            log.warn("[회원가입 실패] reason=duplicate_email email={}", email);
            throw new DuplicateEmailException(email);
        }

        emailVerificationService.consumeForSignup(email, request.verificationToken());

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
