package fruition.access.user.controller;

import fruition.access.AccessExceptionHandler;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.user.dto.UserSettingsResponse;
import fruition.access.user.service.UserSettingsService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserSettingsController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class UserSettingsControllerTest {

    private static final String USER_ID = "user_1f9a74af";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean UserSettingsService userSettingsService;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private String bearerToken() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(USER_ID, "test@example.com");
    }

    @Test
    void update_missingValue_returns400AndKeepsEnabledSetting() throws Exception {
        when(userSettingsService.update(USER_ID, true)).thenReturn(new UserSettingsResponse(true));
        when(userSettingsService.get(USER_ID)).thenReturn(new UserSettingsResponse(true));

        mockMvc.perform(put("/api/auth/me/settings")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"web_search_enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.web_search_enabled").value(true));

        mockMvc.perform(put("/api/auth/me/settings")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/auth/me/settings").header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.web_search_enabled").value(true));

        verify(userSettingsService).update(USER_ID, true);
        verify(userSettingsService, never()).update(USER_ID, false);
    }
}
