package com.unesp.SocialGuard.dto;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;

import java.time.LocalDateTime;
import java.util.List;

public record BatchResponse(
        String batchId,
        String companyId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        BatchStatus status,
        int tokensConsumed,
        String llmModel,
        List<CommentResponse> comments
) {
    public static BatchResponse from(Batch b) {
        return new BatchResponse(b.getBatchId(), b.getCompanyId(), b.getCreatedAt(), b.getUpdatedAt(),
                b.getCompletedAt(), b.getStatus(), b.getTokensConsumed(), b.getLlmModel(),
                b.getComments().stream().map(CommentResponse::from).toList());
    }
}
