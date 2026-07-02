package fruition.security.oauth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2UserInfoTest {

    @Test
    void google_parsesFlatAttributes() {
        OAuth2UserInfo userInfo = new GoogleOAuth2UserInfo(Map.of(
                "sub", "google-123",
                "email", "test@gmail.com",
                "name", "Test User"
        ));

        assertThat(userInfo.getProviderUserId()).isEqualTo("google-123");
        assertThat(userInfo.getEmail()).isEqualTo("test@gmail.com");
        assertThat(userInfo.getName()).isEqualTo("Test User");
    }

    @Test
    void naver_parsesNestedResponseObject() {
        OAuth2UserInfo userInfo = new NaverOAuth2UserInfo(Map.of(
                "response", Map.of(
                        "id", "naver-123",
                        "email", "test@naver.com",
                        "name", "Test User"
                )
        ));

        assertThat(userInfo.getProviderUserId()).isEqualTo("naver-123");
        assertThat(userInfo.getEmail()).isEqualTo("test@naver.com");
        assertThat(userInfo.getName()).isEqualTo("Test User");
    }

    @Test
    void kakao_parsesNestedAccountAndProfile() {
        OAuth2UserInfo userInfo = new KakaoOAuth2UserInfo(Map.of(
                "id", 123456789L,
                "kakao_account", Map.of(
                        "email", "test@kakao.com",
                        "profile", Map.of("nickname", "Test User")
                )
        ));

        assertThat(userInfo.getProviderUserId()).isEqualTo("123456789");
        assertThat(userInfo.getEmail()).isEqualTo("test@kakao.com");
        assertThat(userInfo.getName()).isEqualTo("Test User");
    }

    @Test
    void factory_unknownProvider_throwsException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OAuth2UserInfoFactory.create("facebook", Map.of()));
    }

    @Test
    void factory_knownProviders_createsCorrectType() {
        assertThat(OAuth2UserInfoFactory.create("google", Map.of("sub", "1", "email", "a@b.com", "name", "n")))
                .isInstanceOf(GoogleOAuth2UserInfo.class);
        assertThat(OAuth2UserInfoFactory.create("naver", Map.of("response", Map.of())))
                .isInstanceOf(NaverOAuth2UserInfo.class);
        assertThat(OAuth2UserInfoFactory.create("kakao", Map.of("id", 1L)))
                .isInstanceOf(KakaoOAuth2UserInfo.class);
    }
}
