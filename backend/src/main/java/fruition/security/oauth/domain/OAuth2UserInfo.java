package fruition.security.oauth.domain;

public interface OAuth2UserInfo {

    String getProviderUserId();

    String getEmail();

    String getName();
}
