package fruition.access.security.oauth.handler;

import fruition.access.security.oauth.OAuthExchangeCodeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock OAuthExchangeCodeStore exchangeCodeStore;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock HttpSession session;
    @Mock Authentication authentication;

    @Test
    void success_issuesCodeAndInvalidatesHandshakeSession() throws Exception {
        when(authentication.getName()).thenReturn("user_1f9a74af");
        when(exchangeCodeStore.issue("user_1f9a74af")).thenReturn("exchange-code");
        when(request.getSession(false)).thenReturn(session);
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(session).invalidate();
        verify(response).sendRedirect("http://localhost:3000/oauth/callback?code=exchange-code");
    }
}
