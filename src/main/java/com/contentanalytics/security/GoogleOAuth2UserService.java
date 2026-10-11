package com.contentanalytics.security;

import com.contentanalytics.entity.User;
import com.contentanalytics.exception.AccountLinkingException;
import com.contentanalytics.service.OAuthAccountProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a Google OIDC userinfo response into our own authenticated principal.
 * <p>
 * Runs after Spring Security has already validated the authorization code, {@code state}
 * and {@code nonce} and fetched {@code /v1/userinfo}. Everything below is application
 * policy: require a Google-verified email, find-or-create the local row, then expose the
 * row's id and username as attributes so {@link OAuth2LoginSuccessHandler} can mint the
 * same JWT pair the password login produces.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GoogleOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    /** Our own row id, carried from the provisioner to the success handler. */
    static final String ATTR_USER_ID = "userId";

    /** Our own username, carried from the provisioner to the success handler. */
    static final String ATTR_USERNAME = "username";

    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_EMAIL_VERIFIED = "email_verified";
    private static final String CLAIM_GIVEN_NAME = "given_name";
    private static final String CLAIM_FAMILY_NAME = "family_name";

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
    private final OAuthAccountProvisioner provisioner;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User googleUser = delegate.loadUser(userRequest);
        Map<String, Object> claims = googleUser.getAttributes();

        String subject = required(claims, CLAIM_SUBJECT);
        String email = required(claims, CLAIM_EMAIL);

        // Account linking keys off this email, so refusing to trust it when Google has
        // not vouched for it is the whole point - otherwise anyone who can make Google
        // return an unverified address could attach their identity to someone else's row.
        if (!Boolean.TRUE.equals(claims.get(CLAIM_EMAIL_VERIFIED))) {
            log.warn("Rejecting Google sign-in: email '{}' is not verified by Google", email);
            throw error("invalid_email", "Google did not verify this email address");
        }

        User user;
        try {
            user = provisioner.provisionGoogleAccount(
                    subject,
                    email,
                    text(claims, CLAIM_GIVEN_NAME),
                    text(claims, CLAIM_FAMILY_NAME));
        } catch (AccountLinkingException ex) {
            log.warn("Google account linking refused for {}: {}", email, ex.getMessage());
            throw error("account_link_conflict", ex.getMessage());
        }

        // Copy the claims and stamp our own identifiers on top. Google never uses these
        // two keys, so there is no chance of overwriting a claim we later depend on.
        Map<String, Object> attributes = new HashMap<>(claims);
        attributes.put(ATTR_USER_ID, user.getId().toString());
        attributes.put(ATTR_USERNAME, user.getUsername());

        return new DefaultOAuth2User(googleUser.getAuthorities(), attributes, CLAIM_SUBJECT);
    }

    private String required(Map<String, Object> claims, String key) {
        String value = text(claims, key);
        if (value == null) {
            throw error("missing_claim", "Google did not return the '" + key + "' claim");
        }
        return value;
    }

    private String text(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private OAuth2AuthenticationException error(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
