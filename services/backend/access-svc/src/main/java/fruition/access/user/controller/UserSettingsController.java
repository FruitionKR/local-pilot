package fruition.access.user.controller;

import fruition.access.user.dto.UserSettingsRequest;
import fruition.access.user.dto.UserSettingsResponse;
import fruition.access.user.service.UserSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/me/settings")
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    public UserSettingsController(UserSettingsService userSettingsService) {
        this.userSettingsService = userSettingsService;
    }

    @GetMapping
    public ResponseEntity<UserSettingsResponse> get(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(userSettingsService.get(userId));
    }

    @PutMapping
    public ResponseEntity<UserSettingsResponse> update(
            @AuthenticationPrincipal String userId,
            @RequestBody UserSettingsRequest request) {
        return ResponseEntity.ok(userSettingsService.update(userId, request.webSearchEnabled()));
    }
}
