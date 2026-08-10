package fruition.access.workspace.controller;

import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.shared.util.ErrorResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * core(문서 서비스)가 호출하는 내부 조회 API. 사용자 인증 대상이 아니며
 * X-Internal-Token으로 검증한다(기존 pipeline 콜백과 같은 방식).
 *
 * <p>멤버가 아니어도 404가 아니라 {@code {"role":"NONE"}}을 돌려준다.
 * fail-closed 판정은 호출측(core의 WorkspaceAccessGuard)이 한다.
 */
@RestController
public class InternalAuthzController {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final String internalToken;

    public InternalAuthzController(WorkspaceMemberRepository workspaceMemberRepository,
                                   UserRepository userRepository,
                                   @Value("${app.internal.callback-token}") String internalToken) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.internalToken = internalToken;
    }

    @GetMapping("/internal/authz/workspaces/{workspace_id}/users/{user_id}")
    public ResponseEntity<?> role(
            @PathVariable("workspace_id") String workspaceId,
            @PathVariable("user_id") String userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        String role = workspaceMemberRepository.findActiveRole(workspaceId, userId)
                .map(Enum::name)
                .orElse("NONE");
        return ResponseEntity.ok(new RoleResponse(role));
    }

    @GetMapping("/internal/users/{user_id}")
    public ResponseEntity<?> user(
            @PathVariable("user_id") String userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenMatches(token)) {
            return unauthorized();
        }
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new UserResponse(
                        user.getDisplayName(), user.isWebSearchEnabled())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")));
    }

    /** 길이가 달라도 시간차가 새지 않도록 상수 시간 비교를 쓴다. */
    private boolean tokenMatches(String token) {
        return token != null && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                internalToken.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<ErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_INTERNAL_TOKEN", "내부 토큰이 올바르지 않습니다."));
    }

    record RoleResponse(String role) {}

    record UserResponse(
            @JsonProperty("display_name") String displayName,
            @JsonProperty("web_search_enabled") boolean webSearchEnabled) {}
}
