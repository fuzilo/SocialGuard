package com.unesp.SocialGuard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record BatchResultsRequest(
        @NotEmpty List<@Valid CommentInferenceRequest> comments,
        @PositiveOrZero Integer tokensConsumed,
        String llmModel
) {
}
