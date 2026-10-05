package com.contentanalytics.repository;

import com.contentanalytics.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

    /**
     * Find profile by user ID
     */
    Optional<UserProfile> findByUserId(UUID userId);

    /**
     * Check if profile exists for user
     */
    boolean existsByUserId(UUID userId);

}