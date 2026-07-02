package fruition.security.oauth;

public interface OAuth2UserInfo {

    String getProviderUserId();

    String getEmail();

    String getName();
}
