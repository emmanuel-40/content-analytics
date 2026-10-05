package com.contentanalytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CurrentUserDto {

    private UUID id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String role; // ADMIN, USER
    private String status; // ACTIVE, INACTIVE, BANNED
    private Boolean emailVerified;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

}
