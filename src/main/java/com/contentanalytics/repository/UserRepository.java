package com.contentanalytics.repository;

import com.contentanalytics.entity.AuthProvider;
import com.contentanalytics.entity.User;
import com.contentanalytics.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    //Find user by username
    Optional<User> findByUsername(String username);

    // Find user by email
    Optional<User> findByEmail(String email);

    // Find active user by username (status = ACTIVE)
    @Query("SELECT u FROM User u WHERE u.username = :username AND u.status = 'ACTIVE'")
    Optional<User> findActiveByUsername(@Param("username") String username);

    // Check if username exists
    boolean existsByUsername(String username);

    // Check if email exists
    boolean existsByEmail(String email);

    // Find an account by the identity it is linked to (e.g. Google's `sub` claim).
    // Runs on every OAuth callback so returning users land on the same row.
    Optional<User> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

    // Find all active users with pagination
    Page<User> findByStatus(UserStatus status, Pageable pageable);

    // Count users created in a date range
    long countByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

    // Find users who haven't logged in for X days
    @Query("SELECT u FROM User u WHERE u.lastLoginAt < :cutoffDate OR u.lastLoginAt IS NULL")
    Page<User> findInactiveUsers(@Param("cutoffDate") LocalDateTime cutoffDate, Pageable pageable);

}
