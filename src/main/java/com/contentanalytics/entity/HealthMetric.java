package com.contentanalytics.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "health_metrics", indexes = {
        @Index(name = "idx_check_type", columnList = "check_type"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String checkType; // database, redis, disk, memory, api

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HealthStatus status; // HEALTHY, DEGRADED, UNHEALTHY

    @Column
    private Long responseTimeMs; // Response time in milliseconds

    @Column(columnDefinition = "TEXT")
    private String message; // Status message or error details

    @Column
    private Float cpuUsagePercent;

    @Column
    private Float memoryUsagePercent;

    @Column
    private Long diskAvailableBytes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
