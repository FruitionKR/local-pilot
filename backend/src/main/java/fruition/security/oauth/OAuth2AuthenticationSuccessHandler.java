package fruition.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthExchangeCodeStore exchangeCodeStore;
    private final String frontendRedirectUri;

    public OAuth2AuthenticationSuccessHandler(OAuthExchangeCodeStore exchangeCodeStore,
                                              @Value("${app.oauth.frontend-redirect-uri}") String frontendRedirectUri) {
        this.exchangeCodeStore = exchangeCodeStore;
        this.frontendRedirectUri = frontendRedirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String userId = authentication.getName();
        String code = exchangeCodeStore.issue(userId);

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("code", code)
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }
}
