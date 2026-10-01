package com.unesp.SocialGuard.dto;

import jakarta.validation.constraints.NotBlank;

public record CommentRequest(
        String postId,
        String commentId,
        String name,
        String email,
        @NotBlank String body
) {
}
