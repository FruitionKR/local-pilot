package fruition.access.workspace.controller;

import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.AccessExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * core(문서 서비스)가 호출하는 내부 조회 API. 사용자 JWT가 아니라 내부 토큰으로 지킨다.
 * 토큰을 통과하기 전에는 저장소에 닿지 않아야 한다.
 */
@WebMvcTest(InternalAuthzController.class)
@Import({AccessExceptionHandler.class, SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class,
        OAuthExchangeCodeStore.class, OAuth2AuthenticationSuccessHandler.class, OAuth2AuthenticationFailureHandler.class})
class InternalAuthzControllerTest {

    private static final String ROLE_URL = "/internal/authz/workspaces/ws_1/users/user_1";
    private static final String USER_URL = "/internal/users/user_1";

    @Autowired MockMvc mockMvc;
    @MockBean WorkspaceMemberRepository workspaceMemberRepository;
    @MockBean UserRepository userRepository;
    @MockBean CustomOAuth2UserService customOAuth2UserService;
    // OAuthExchangeCodeStore가 Redis에 의존하므로 web slice에는 mock template을 채운다.
    @MockBean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Value("${app.internal.callback-token}")
    String internalToken;

    @Test
    @DisplayName("토큰이 없으면 401이고 저장소에 닿지 않는다")
    void role_withoutToken_rejects() throws Exception {
        mockMvc.perform(get(ROLE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_INTERNAL_TOKEN"));

        verify(workspaceMemberRepository, never()).findActiveRole(any(), any());
    }

    @Test
    @DisplayName("멤버면 역할을 그대로 돌려준다")
    void role_member_returnsRole() throws Exception {
        when(workspaceMemberRepository.findActiveRole("ws_1", "user_1"))
                .thenReturn(Optional.of(WorkspaceRole.OWNER));

        mockMvc.perform(get(ROLE_URL).header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    @DisplayName("멤버가 아니면 404가 아니라 200 NONE이다 — 판정은 호출측이 한다")
    void role_nonMember_returnsNone() throws Exception {
        when(workspaceMemberRepository.findActiveRole("ws_1", "user_1"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(ROLE_URL).header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("NONE"));
    }

    @Test
    @DisplayName("사용자 조회는 표시명을 snake_case로 돌려준다")
    void user_found_returnsDisplayName() throws Exception {
        when(userRepository.findById("user_1"))
                .thenReturn(Optional.of(new User("user_1", "test@example.com", User.PROVIDER_LOCAL, "테스터", null)));

        mockMvc.perform(get(USER_URL).header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("테스터"));
    }

    @Test
    @DisplayName("없는 사용자는 404다")
    void user_missing_returns404() throws Exception {
        when(userRepository.findById("user_1")).thenReturn(Optional.empty());

        mockMvc.perform(get(USER_URL).header("X-Internal-Token", internalToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("사용자 조회도 토큰 없이는 401이다")
    void user_withoutToken_rejects() throws Exception {
        mockMvc.perform(get(USER_URL))
                .andExpect(status().isUnauthorized());

        verify(userRepository, never()).findById(any());
    }
}
