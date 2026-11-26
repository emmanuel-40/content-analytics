package com.contentanalytics.security;

import com.contentanalytics.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Google OIDC principal mapping")
class GoogleOidcUserServiceTest {

    @Test
    @DisplayName("the OIDC principal carries the local user id expected by the success handler")
    void oidcPrincipalCarriesLocalUserId() {
        GoogleOAuth2UserService googleOAuth2UserService = mock(GoogleOAuth2UserService.class);
        OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        OidcUserRequest request = mock(OidcUserRequest.class);
        OidcUser googleUser = mock(OidcUser.class);

        UUID localId = UUID.randomUUID();
        User localUser = User.builder().id(localId).username("ada").build();
        Map<String, Object> claims = Map.of(
                "sub", "google-sub-123",
                "email", "ada@example.com",
                "email_verified", true);

        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));

        when(delegate.loadUser(request)).thenReturn(googleUser);
        when(googleUser.getAttributes()).thenReturn(claims);
        doReturn(authorities).when(googleUser).getAuthorities();
        when(googleUser.getIdToken()).thenReturn(new OidcIdToken(
                "id-token", Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                Map.of("sub", "google-sub-123")));
        when(googleOAuth2UserService.provisionFromClaims(anyMap())).thenReturn(localUser);

        OidcUser result = new GoogleOidcUserService(googleOAuth2UserService, delegate).loadUser(request);

        assertThat(result.<String>getAttribute("userId")).isEqualTo(localId.toString());
        assertThat(result.<String>getAttribute("username")).isEqualTo("ada");
        assertThat(result.<String>getAttribute("sub")).isEqualTo("google-sub-123");
    }
}
