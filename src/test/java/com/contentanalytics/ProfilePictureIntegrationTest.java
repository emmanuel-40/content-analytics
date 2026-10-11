package com.contentanalytics;

import com.contentanalytics.dto.UserProfileDto;
import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.ProfilePicture;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserRole;
import com.contentanalytics.entity.UserStatus;
import com.contentanalytics.repository.ProfilePictureRepository;
import com.contentanalytics.repository.UserProfileRepository;
import com.contentanalytics.repository.UserRepository;
import com.contentanalytics.service.ProfileService;
import com.contentanalytics.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that boot the real Spring context against in-memory H2.
 *
 * <p>These exist because the bugs they cover are invisible to unit tests: both were
 * constraint violations that only a real database could raise.
 *
 * <ul>
 *   <li>{@code Optional.orElse(createDefaultProfile(user))} evaluated its argument
 *       <em>eagerly</em>, so every profile read inserted a {@code user_profiles} row.</li>
 *   <li>{@code profile_pictures.user_id} is UNIQUE, but upload inserted a second row
 *       instead of updating the first, so re-uploading an avatar returned a 500.</li>
 * </ul>
 *
 * <p>The {@code test} profile pins the datasource to H2 in
 * {@code src/test/resources/application-test.properties}; {@link #testsRunAgainstH2()}
 * is a tripwire that fails loudly if that is ever bypassed.
 */
@ActiveProfiles("test")
@SpringBootTest
@DisplayName("Profile picture persistence (integration)")
class ProfilePictureIntegrationTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ProfilePictureRepository profilePictureRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    private User user;

    @BeforeEach
    void setUp() {
        profilePictureRepository.deleteAll();
        userProfileRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(User.builder()
                .username("itest-" + UUID.randomUUID().toString().substring(0, 8))
                .email("itest-" + UUID.randomUUID() + "@example.com")
                .password("$2a$10$placeholderHashPlaceholderPlaceholderPlaceholder")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .tokenVersion(1)
                .emailVerified(true)
                .provider(AuthProvider.LOCAL)
                .build());
    }

    @Test
    @DisplayName("tests never reach a real database - H2 only")
    void testsRunAgainstH2() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();

            assertThat(url)
                    .as("the test datasource must be in-memory H2, never Postgres/Supabase")
                    .startsWith("jdbc:h2:");
            assertThat(url.toLowerCase())
                    .doesNotContain("postgresql");
        }
    }

    @Test
    @DisplayName("reading the profile repeatedly does not insert duplicate rows")
    void repeatedProfileReadsDoNotCreateDuplicateProfiles() {
        for (int i = 0; i < 5; i++) {
            assertThat(profileService.getUserProfile(user.getId())).isNotNull();
        }

        assertThat(userProfileRepository.findAll())
                .as("orElse(createDefaultProfile) used to insert on every single read")
                .hasSize(1);
    }

    @Test
    @DisplayName("a user can replace their avatar without hitting the unique constraint")
    void avatarCanBeUploadedMoreThanOnce() throws Exception {
        profileService.uploadProfilePicture(user.getId(), png("first.png"), "127.0.0.1");
        profileService.uploadProfilePicture(user.getId(), png("second.png"), "127.0.0.1");
        profileService.uploadProfilePicture(user.getId(), png("third.png"), "127.0.0.1");

        assertThat(profilePictureRepository.findAll())
                .as("profile_pictures.user_id is UNIQUE - uploads must upsert, not insert")
                .hasSize(1);

        assertThat(profilePictureRepository.findByUserId(user.getId())).isPresent();

        UserProfileDto profile = profileService.getUserProfile(user.getId());
        assertThat(profile.getAvatarUrl()).isNotBlank();
        assertThat(profile.getThumbnailUrl()).isNotBlank();
    }

    @Test
    @DisplayName("stored keys resolve to a loadable URL and the bytes are really written")
    void avatarBytesAreWrittenToStorage() throws Exception {
        UserProfileDto profile = profileService.uploadProfilePicture(
                user.getId(), png("avatar.png"), "127.0.0.1");

        assertThat(profile.getAvatarUrl())
                .as("local storage serves through the authenticated endpoint")
                .startsWith("/api/profile/picture/file?key=profile-" + user.getId() + "-");

        ProfilePicture picture = profilePictureRepository.findByUserId(user.getId()).orElseThrow();

        // storageKey and fullUrl intentionally hold the same key.
        assertThat(picture.getStorageKey()).isEqualTo(picture.getFullUrl());
        assertThat(picture.getMediumUrl()).isNotBlank();
        assertThat(picture.getThumbnailUrl()).isNotBlank();

        // The key must resolve to real bytes, not just a row in the database.
        for (String key : new String[] {
                picture.getStorageKey(), picture.getMediumUrl(), picture.getThumbnailUrl() }) {
            Resource resource = storageService.loadFileAsResource(key);
            assertThat(resource.exists())
                    .as("image variant %s should have been written to storage", key)
                    .isTrue();
            assertThat(resource.getContentAsByteArray()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("deleting the avatar frees its bytes and falls back to Gravatar")
    void deletingAvatarRemovesStoredBytes() throws Exception {
        UserProfileDto uploaded = profileService.uploadProfilePicture(
                user.getId(), png("avatar.png"), "127.0.0.1");
        assertThat(uploaded.getAvatarUrl()).startsWith("/api/profile/picture/file?key=profile-");

        ProfilePicture picture = profilePictureRepository.findByUserId(user.getId()).orElseThrow();
        String key = picture.getStorageKey();
        assertThat(storageService.loadFileAsResource(key).exists()).isTrue();

        profileService.deleteProfilePicture(user.getId());

        assertThat(storageService.loadFileAsResource(key).exists())
                .as("replaced/deleted image objects must not be orphaned in storage")
                .isFalse();

        UserProfileDto after = profileService.getUserProfile(user.getId());
        assertThat(after.getAvatarUrl())
                .as("no stored picture means falling back to Gravatar")
                .startsWith("https://www.gravatar.com/avatar/");
    }

    /**
     * Build a valid 400x400 PNG. {@code ImageService} rejects anything it cannot decode,
     * so a byte-array placeholder is not enough.
     */
    private static MockMultipartFile png(String filename) throws Exception {
        BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 400, 400);
        graphics.dispose();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);

        return new MockMultipartFile("file", filename, "image/png", bytes.toByteArray());
    }
}
