package com.contentanalytics.repository;

import com.contentanalytics.entity.ProfilePicture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfilePictureRepository extends JpaRepository<ProfilePicture, UUID> {

    /**
     * Find profile picture by user ID
     */
    Optional<ProfilePicture> findByUserId(UUID userId);

    /**
     * Check if user has profile picture
     */
    boolean existsByUserIdAndIsActiveTrue(UUID userId);

    /**
     * Delete all inactive pictures older than X days
     */
    void deleteByIsActiveFalseAndCreatedAtBefore(java.time.LocalDateTime date);

}