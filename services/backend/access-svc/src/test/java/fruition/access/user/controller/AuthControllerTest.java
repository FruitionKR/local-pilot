package fruition.access.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.dto.EmailAvailabilityRequest;
import fruition.access.user.dto.EmailAvailabilityResponse;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.EmailVerificationResponse;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.DisplayNameUpdateRequest;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.dto.PasswordChangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.SignupResponse;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.dto.VerificationConfirmResponse;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.service.AuthService;
import fruition.access.user.service.EmailAvailabilityRateLimiter;
import fruition.access.user.service.EmailVerificationService;
import fruition.access.user.service.UserService;
import fruition.access.AccessExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean UserService userService;
    @MockBean AuthService authService;
    @MockBean EmailAvailabilityRateLimiter emailAvailabilityRateLimiter;
    @MockBean EmailVerificationService emailVerificationService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Test
    void checkEmailAvailability_existingEmail_returnsFalse() throws Exception {
        when(userService.checkEmailAvailability(any())).thenReturn(new EmailAvailabilityResponse(false));

        mockMvc.perform(post("/api/auth/email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailAvailabilityRequest("test@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void checkEmailAvailability_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailAvailabilityRequest("invalid"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void checkEmailAvailability_rateLimited_returns429() throws Exception {
        doThrow(new EmailAvailabilityRateLimitedException(60))
                .when(emailAvailabilityRateLimiter).check(any(), any());

        mockMvc.perform(post("/api/auth/email-availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailAvailabilityRequest("test@example.com"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("EMAIL_AVAILABILITY_RATE_LIMITED"));
    }

    @Test
    void signup_validRequest_returns201() throws Exception {
        when(userService.signup(any())).thenReturn(
                new SignupResponse("user_1f9a74af", "test@example.com", "test", Instant.now()));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("test@example.com", "password123", null, "verification-token"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user_1f9a74af"))
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    void signup_missingVerificationToken_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("test@example.com", "password123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void signup_duplicateEmail_returns409() throws Exception {
        when(userService.signup(any())).thenThrow(new DuplicateEmailException("test@example.com"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("test@example.com", "password123", null, "verification-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void requestEmailVerification_valid_returns202() throws Exception {
        when(emailVerificationService.request(any())).thenReturn(
                new EmailVerificationResponse("ev_abc123", 300, 60));

        mockMvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailVerificationRequest("test@example.com", "signup"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.verification_id").value("ev_abc123"))
                .andExpect(jsonPath("$.expires_in").value(300));
    }

    @Test
    void requestEmailVerification_invalidPurpose_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailVerificationRequest("test@example.com", "unknown"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void confirmEmailVerification_valid_returns200() throws Exception {
        when(emailVerificationService.confirm(any(), any())).thenReturn(
                new VerificationConfirmResponse("verification-token", 600));

        mockMvc.perform(post("/api/auth/email-verifications/ev_abc123/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerificationConfirmRequest("123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification_token").value("verification-token"));
    }

    @Test
    void confirmEmailVerification_wrongCode_returns400() throws Exception {
        when(emailVerificationService.confirm(any(), any())).thenThrow(new InvalidVerificationCodeException());

        mockMvc.perform(post("/api/auth/email-verifications/ev_abc123/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VerificationConfirmRequest("000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void resetPassword_valid_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetRequest("test@example.com", "newpassword123", "verification-token"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void signup_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("not-an-email", "password123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void signup_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("test@example.com", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void login_validCredentials_returns200WithTokens() throws Exception {
        when(authService.login(any())).thenReturn(
                new LoginResponse("access-token", "refresh-token", "Bearer", 900));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-token"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("fruition_refresh_token=refresh-token"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"))));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("test@example.com", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refresh_validToken_returns200WithNewTokens() throws Exception {
        when(authService.refresh(any())).thenReturn(
                new LoginResponse("new-access-token", "new-refresh-token", "Bearer", 900));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("fruition_refresh_token", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access-token"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist());
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        when(authService.refresh(any())).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("fruition_refresh_token", "bad-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refresh_withoutCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_validToken_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("fruition_refresh_token", "refresh-token")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void logout_withoutCookie_isIdempotent() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void me_withValidAccessToken_returns200() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
        when(authService.me("user_1f9a74af")).thenReturn(
                new MeResponse("user_1f9a74af", "test@example.com", "tes", Instant.now()));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user_1f9a74af"));
    }

    @Test
    void me_withoutAccessToken_returns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exchangeOAuthCode_validCode_returns200WithTokens() throws Exception {
        when(authService.exchangeOAuthCode(any())).thenReturn(
                new LoginResponse("access-token", "refresh-token", "Bearer", 900));

        mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthExchangeRequest("some-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("access-token"));
    }

    @Test
    void exchangeOAuthCode_invalidCode_returns401() throws Exception {
        when(authService.exchangeOAuthCode(any())).thenThrow(new InvalidOAuthCodeException());

        mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthExchangeRequest("bad-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_CODE"));
    }

    @Test
    void updateDisplayName_authenticated_returns200() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
        when(authService.updateDisplayName(eq("user_1f9a74af"), any())).thenReturn(
                new MeResponse("user_1f9a74af", "test@example.com", "새 이름", Instant.now()));

        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DisplayNameUpdateRequest("새 이름"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("새 이름"));
    }

    @Test
    void updateDisplayName_unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/auth/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DisplayNameUpdateRequest("새 이름"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateDisplayName_blank_returns400() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");

        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"display_name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void changePassword_authenticated_returns204() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");

        mockMvc.perform(put("/api/auth/me/password")
                        .header("Authorization", "Bearer " + token)
                        .cookie(new jakarta.servlet.http.Cookie("fruition_refresh_token", "current-refresh"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("oldPassword1", "newPassword1"))))
                .andExpect(status().isNoContent());
        verify(authService).changePassword("user_1f9a74af",
                new PasswordChangeRequest("oldPassword1", "newPassword1"), "current-refresh");
    }

    @Test
    void changePassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/auth/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("oldPassword1", "newPassword1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_shortNewPassword_returns400() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");

        mockMvc.perform(put("/api/auth/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("oldPassword1", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void changePassword_wrongCurrentPassword_returns401() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
        doThrow(new InvalidCredentialsException())
                .when(authService).changePassword(eq("user_1f9a74af"), any(), any());

        mockMvc.perform(put("/api/auth/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("wrongPassword", "newPassword1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void changeEmail_authenticated_returns200() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
        when(authService.changeEmail(eq("user_1f9a74af"), any(), eq("current-refresh"))).thenReturn(
                new MeResponse("user_1f9a74af", "new@example.com", "이름", Instant.now()));

        mockMvc.perform(put("/api/auth/me/email")
                        .header("Authorization", "Bearer " + token)
                        .cookie(new jakarta.servlet.http.Cookie("fruition_refresh_token", "current-refresh"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailChangeRequest("new@example.com", "verification-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void changeEmail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/auth/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailChangeRequest("new@example.com", "verification-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeEmail_invalidEmailFormat_returns400() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");

        mockMvc.perform(put("/api/auth/me/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"new_email\":\"not-an-email\",\"verification_token\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void changeEmail_duplicate_returns409() throws Exception {
        String token = jwtTokenProvider.generateAccessToken("user_1f9a74af", "test@example.com");
        when(authService.changeEmail(eq("user_1f9a74af"), any(), any()))
                .thenThrow(new DuplicateEmailException("taken@example.com"));

        mockMvc.perform(put("/api/auth/me/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailChangeRequest("taken@example.com", "verification-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void requestVerification_emailChangePurpose_isAccepted() throws Exception {
        when(emailVerificationService.request(any()))
                .thenReturn(new EmailVerificationResponse("ev_1", 300, 60));

        mockMvc.perform(post("/api/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"purpose\":\"email_change\"}"))
                .andExpect(status().isAccepted());
    }
}
