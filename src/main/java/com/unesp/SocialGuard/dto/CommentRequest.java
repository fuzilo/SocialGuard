package com.unesp.SocialGuard.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public record CommentRequest(
        String postId,
        String commentId,
        String name,
        String email,
        @NotBlank String body,
        LocalDateTime createdAt,
        LocalDateTime updatedAt

) {
}
