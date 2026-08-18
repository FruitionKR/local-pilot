package fruition.access.user.controller;

import fruition.access.user.dto.EmailAvailabilityRequest;
import fruition.access.user.dto.EmailAvailabilityResponse;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.EmailVerificationResponse;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.SignupResponse;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.dto.VerificationConfirmResponse;
import fruition.access.user.service.AuthService;
import fruition.access.user.service.EmailAvailabilityRateLimiter;
import fruition.access.user.service.EmailVerificationService;
import fruition.access.user.service.UserService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "회원가입 및 인증 API")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final EmailAvailabilityRateLimiter emailAvailabilityRateLimiter;
    private final EmailVerificationService emailVerificationService;

    public AuthController(UserService userService, AuthService authService,
                          EmailAvailabilityRateLimiter emailAvailabilityRateLimiter,
                          EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.authService = authService;
        this.emailAvailabilityRateLimiter = emailAvailabilityRateLimiter;
        this.emailVerificationService = emailVerificationService;
    }

    @Operation(summary = "회원가입 이메일 중복 확인", description = "이메일로 신규 가입할 수 있는지 확인합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "가입 가능 여부",
            content = @Content(schema = @Schema(implementation = EmailAvailabilityResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "요청 횟수 제한 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/email-availability")
    public ResponseEntity<EmailAvailabilityResponse> checkEmailAvailability(
            @Valid @RequestBody EmailAvailabilityRequest request,
            HttpServletRequest servletRequest) {
        emailAvailabilityRateLimiter.check(request.email(), servletRequest.getRemoteAddr());
        return ResponseEntity.ok(userService.checkEmailAvailability(request));
    }

    @Operation(summary = "이메일 인증번호 발급", description = "회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "인증번호 발급",
            content = @Content(schema = @Schema(implementation = EmailVerificationResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 가입된 이메일(purpose=signup)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "429", description = "재요청 제한 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/email-verifications")
    public ResponseEntity<EmailVerificationResponse> requestEmailVerification(
            @Valid @RequestBody EmailVerificationRequest request) {
        EmailVerificationResponse response = emailVerificationService.request(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "이메일 인증번호 검증", description = "인증번호를 검증하고 1회용 verification_token을 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "검증 성공",
            content = @Content(schema = @Schema(implementation = VerificationConfirmResponse.class))),
        @ApiResponse(responseCode = "400", description = "인증번호 불일치·만료·시도 초과",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "인증 요청을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/email-verifications/{verification_id}/confirm")
    public ResponseEntity<VerificationConfirmResponse> confirmEmailVerification(
            @PathVariable("verification_id") String verificationId,
            @Valid @RequestBody VerificationConfirmRequest request) {
        return ResponseEntity.ok(emailVerificationService.confirm(verificationId, request));
    }

    @Operation(summary = "비밀번호 재설정", description = "verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "재설정 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 또는 유효하지 않은 토큰",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/password-reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "회원가입", description = "이메일/비밀번호로 신규 사용자를 생성합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "회원가입 성공",
            content = @Content(schema = @Schema(implementation = SignupResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "이미 가입된 이메일",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = userService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "로그인", description = "이메일/비밀번호를 검증하고 access/refresh token을 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "토큰 재발급", description = "refresh token을 검증하고 access/refresh token을 새로 발급합니다. 기존 refresh token은 폐기됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "재발급 성공",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 refresh token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @Operation(summary = "로그아웃", description = "refresh token을 폐기합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "로그아웃 성공"),
        @ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 refresh token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "OAuth code 교환", description = "OAuth 로그인 성공 후 발급된 1회용 code를 access/refresh token으로 교환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "교환 성공",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/oauth/exchange")
    public ResponseEntity<LoginResponse> exchangeOAuthCode(@Valid @RequestBody OAuthExchangeRequest request) {
        return ResponseEntity.ok(authService.exchangeOAuthCode(request));
    }

    @Operation(summary = "내 정보 조회", description = "access token으로 인증된 사용자의 프로필을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공",
            content = @Content(schema = @Schema(implementation = MeResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증되지 않음",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(authService.me(userId));
    }
}
