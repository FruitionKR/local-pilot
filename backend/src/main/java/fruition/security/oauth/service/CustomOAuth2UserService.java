package fruition.security.oauth.service;

import fruition.security.oauth.domain.OAuth2UserInfo;

import fruition.user.domain.User;
import fruition.user.service.OAuthUserService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final String INTERNAL_USER_ID_ATTRIBUTE = "internal_user_id";

    private final OAuthUserService oAuthUserService;

    public CustomOAuth2UserService(OAuthUserService oAuthUserService) {
        this.oAuthUserService = oAuthUserService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.create(registrationId, oAuth2User.getAttributes());

        User user = oAuthUserService.findOrCreateUser(registrationId, userInfo);

        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        attributes.put(INTERNAL_USER_ID_ATTRIBUTE, user.getId());

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                INTERNAL_USER_ID_ATTRIBUTE
        );
    }
}
