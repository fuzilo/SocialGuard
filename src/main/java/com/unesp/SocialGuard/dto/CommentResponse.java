package com.unesp.SocialGuard.dto;

import com.unesp.SocialGuard.domain.Comment;

import java.time.LocalDateTime;

public record CommentResponse(
        Long id,
        String postId,
        String commentId,
        String name,
        String email,
        String body,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String inferenceResult
) {
    public static CommentResponse from(Comment c) {
        return new CommentResponse(c.getId(), c.getPostId(), c.getCommentId(), c.getName(), c.getEmail(),
                c.getBody(), c.getCreatedAt(), c.getUpdatedAt(), c.getInferenceResult());
    }
}
