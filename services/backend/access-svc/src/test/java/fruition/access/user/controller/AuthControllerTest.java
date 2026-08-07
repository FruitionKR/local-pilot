package fruition.access.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
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
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.service.AuthService;
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

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    @MockBean EmailVerificationService emailVerificationService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

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
                .andExpect(jsonPath("$.refresh_token").value("refresh-token"));
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("old-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("new-access-token"));
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        when(authService.refresh(any())).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("bad-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_validToken_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("refresh-token"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_invalidToken_returns401() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidRefreshTokenException()).when(authService).logout(any());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("bad-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
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
}
