package com.contentanalytics.security;

import com.contentanalytics.dto.LoginRequestDto;
import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.exception.AccountLinkingException;
import com.contentanalytics.exception.InvalidTokenException;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.AuthService;
import com.contentanalytics.service.OAuthAccountProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google sign-up, end to end against the in-memory H2 schema.
 * <p>
 * The Google side of the handshake (consent screen, code issuance, userinfo) cannot be
 * exercised here - there is no network and no credentials in the test profile. What is
 * under test is everything <i>we</i> own: the find-or-create/link rules, the shape of
 * the account that comes out, the browser-facing entry point and failure vocabulary,
 * and that none of it changed how password login behaves.
 * <p>
 * Every fixture email contains {@code goauth-test} so cleanup is scoped to rows this
 * class created and it cannot delete data belonging to another test class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Google sign-up")
class GoogleOAuthIntegrationTest {

    private static final String MARKER = "goauth-test";
    private static final String SUBJECT_A = "google-sub-aaaaaaaaaaaaaaaa";
    private static final String SUBJECT_B = "google-sub-bbbbbbbbbbbbbbbb";

    @Autowired
    private OAuthAccountProvisioner provisioner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private OAuth2LoginFailureHandler failureHandler;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void removeFixturesFromPreviousTest() {
        userRepository.findAll().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().contains(MARKER))
                .forEach(userRepository::delete);
    }

    private String email(String localPart) {
        return localPart + "@" + MARKER + ".example";
    }

    // ---------------------------------------------------------------------
    // Account provisioning
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("first Google sign-up creates a verified account with no password")
    void firstGoogleSignUpCreatesPasswordlessVerifiedAccount() {
        User created = provisioner.provisionGoogleAccount(SUBJECT_A, email("new.user"), "Ada", "Lovelace");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(created.getProviderSubject()).isEqualTo(SUBJECT_A);
        assertThat(created.getEmail()).isEqualTo(email("new.user"));
        assertThat(created.getPassword()).as("no password is invented for a Google account").isNull();
        assertThat(created.getEmailVerified()).isTrue();
        assertThat(created.getEmailVerifiedAt()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getRole()).isEqualTo(UserRole.USER);
        assertThat(created.getFirstName()).isEqualTo("Ada");
        assertThat(created.getLastName()).isEqualTo("Lovelace");
        // tokenVersion 1 is what makes the first issued refresh token valid; 0 would
        // fail the rotation check on the very first refresh.
        assertThat(created.getTokenVersion()).isEqualTo(1);
        assertThat(created.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("a returning Google user is matched by subject, not by email")
    void returningGoogleUserIsMatchedByProviderSubject() {
        User first = provisioner.provisionGoogleAccount(SUBJECT_A, email("returning"), "Ada", null);

        // Same identity, different casing on the email: must land on the same row.
        User second = provisioner.provisionGoogleAccount(
                SUBJECT_A, email("returning").toUpperCase(), "Ada", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, SUBJECT_A))
                .isPresent();
    }

    @Test
    @DisplayName("Google sign-in links an existing local account by email")
    void googleSignInLinksExistingLocalAccount() {
        String subject = SUBJECT_A + "-link";
        User local = userRepository.save(localUser("already", email("already")));
        assertThat(local.getEmailVerified()).isFalse();
        assertThat(local.getProvider()).isEqualTo(AuthProvider.LOCAL);

        User linked = provisioner.provisionGoogleAccount(subject, email("already"), "Grace", "Hopper");

        assertThat(linked.getId()).isEqualTo(local.getId());
        assertThat(linked.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(linked.getProviderSubject()).isEqualTo(subject);
        assertThat(linked.getEmailVerified())
                .as("Google vouched for the address, so the verification email is redundant")
                .isTrue();
        assertThat(linked.getEmailVerifiedAt()).isNotNull();
        // The password survives, so the owner can still sign in either way.
        assertThat(linked.getPassword()).isNotNull();
        assertThat(userRepository.findAll().stream().filter(u -> u.getEmail().contains(MARKER)).count())
                .as("linking must not create a second row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an account bound to a different Google identity is never re-pointed")
    void googleSignInRefusesToStealBoundAccount() {
        User bound = provisioner.provisionGoogleAccount(SUBJECT_A, email("contested"), "A", null);
        assertThat(bound.getProviderSubject()).isEqualTo(SUBJECT_A);

        // A second, unrelated Google identity claims the same address.
        assertThatThrownBy(() -> provisioner.provisionGoogleAccount(SUBJECT_B, email("contested"), "B", null))
                .isInstanceOf(AccountLinkingException.class)
                .hasMessageContaining("already linked");

        // ...and the original binding is untouched.
        assertThat(userRepository.findByEmail(email("contested")))
                .hasValueSatisfying(u -> assertThat(u.getProviderSubject()).isEqualTo(SUBJECT_A));
    }

    @Test
    @DisplayName("derived username is de-duplicated against existing accounts")
    void derivedUsernameIsDeduplicated() {
        userRepository.save(localUser("collision", email("taken")));

        User created = provisioner.provisionGoogleAccount(SUBJECT_A, email("collision"), null, null);

        assertThat(created.getUsername()).isEqualTo("collision-1");
        assertThat(userRepository.existsByUsername("collision")).isTrue();
    }

    @Test
    @DisplayName("username is sanitised from the email local part")
    void usernameIsSanitisedFromEmail() {
        User created = provisioner.provisionGoogleAccount(
                SUBJECT_A + "-sanitise", "Weird.Name+work@" + MARKER + ".example", null, null);

        assertThat(created.getUsername()).isEqualTo("weird.namework");
        assertThat(created.getEmail()).isEqualTo("weird.name+work@" + MARKER + ".example");
    }

    // ---------------------------------------------------------------------
    // Interaction with the password path
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("password login against a Google-only account gives an actionable error")
    void passwordLoginOnGoogleOnlyAccountIsActionable() {
        User created = provisioner.provisionGoogleAccount(SUBJECT_A, email("nopassword"), null, null);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto(created.getUsername(), "hunter2")))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Google");
    }

    private User localUser(String username, String accountEmail) {
        return User.builder()
                .username(username)
                .email(accountEmail)
                .password("$2a$12$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUV1234567890ab")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(false)
                .provider(AuthProvider.LOCAL)
                .providerSubject(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ---------------------------------------------------------------------
    // Browser-facing surface
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the sign-in entry point redirects to Google's consent screen")
    void authorizationEndpointRedirectsToGoogle() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")))
                // state is the CSRF defence for the callback and must be present.
                .andExpect(header().string("Location", containsString("state=")));
    }

    @Test
    @DisplayName("the callback leg is publicly reachable without a session")
    void callbackLegIsPermitted() throws Exception {
        // Without a valid authorization request Spring rejects this immediately, so
        // the only thing being asserted is that it is not bounced by the security
        // chain to a login page first - i.e. that /login/oauth2/** is permitAll.
        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("code", "not-a-real-code")
                        .param("state", "not-a-real-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/auth/callback")));
    }

    @Test
    @DisplayName("/api/auth/providers reports Google as unconfigured in the test profile")
    void providersEndpointReportsConfiguredFlag() throws Exception {
        mockMvc.perform(get("/api/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(false));
    }

    @Test
    @DisplayName("failure handler redirects with a fixed-vocabulary reason")
    void failureHandlerRedirectsWithFixedVocabulary() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("access_denied", "user said no", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?status=error&reason=access_denied");

        // A link-conflict surfaces as its own reason so the SPA can explain it...
        MockHttpServletResponse conflict = new MockHttpServletResponse();
        failureHandler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                conflict,
                new OAuth2AuthenticationException(
                        new OAuth2Error("account_link_conflict", "already linked to another account", null)));
        assertThat(conflict.getRedirectedUrl()).contains("reason=link_conflict");

        // ...but anything unmapped collapses to a generic reason: the exception
        // message never reaches the URL, so nothing attacker-controlled can be
        // reflected back into the page that receives the redirect.
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        failureHandler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                unknown,
                new BadCredentialsException("https://evil.example/phish"));
        assertThat(unknown.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/auth/callback?status=error&reason=error");
    }
}
