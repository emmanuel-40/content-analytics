package com.contentanalytics.security;

import com.contentanalytics.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * OIDC counterpart of {@link GoogleOAuth2UserService}.
 *
 * Google's default registration asks for the {@code openid} scope, so Spring Security
 * uses this service rather than the plain OAuth2 one. Without it the Google principal
 * never receives our local {@code userId}, and the success handler correctly refuses
 * to mint a session for an unknown user.
 */
@Component
public class GoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final GoogleOAuth2UserService googleOAuth2UserService;

    @Autowired
    public GoogleOidcUserService(GoogleOAuth2UserService googleOAuth2UserService) {
        this(googleOAuth2UserService, new OidcUserService());
    }

    /** Also lets a unit test replace the network-backed delegate. */
    GoogleOidcUserService(GoogleOAuth2UserService googleOAuth2UserService,
                          OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.googleOAuth2UserService = googleOAuth2UserService;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser googleUser = delegate.loadUser(userRequest);
        User user = googleOAuth2UserService.provisionFromClaims(googleUser.getAttributes());

        // The id-token claims take precedence when DefaultOidcUser merges these maps.
        // Our keys are unique to us, so adding them to userinfo guarantees they survive
        // into getAttributes() and reach OAuth2LoginSuccessHandler.
        Map<String, Object> userInfoClaims = new HashMap<>();
        if (googleUser.getUserInfo() != null) {
            userInfoClaims.putAll(googleUser.getUserInfo().getClaims());
        }
        userInfoClaims.put(GoogleOAuth2UserService.ATTR_USER_ID, user.getId().toString());
        userInfoClaims.put(GoogleOAuth2UserService.ATTR_USERNAME, user.getUsername());

        return new DefaultOidcUser(
                googleUser.getAuthorities(),
                googleUser.getIdToken(),
                new OidcUserInfo(userInfoClaims),
                GoogleOAuth2UserService.CLAIM_SUBJECT);
    }
}
