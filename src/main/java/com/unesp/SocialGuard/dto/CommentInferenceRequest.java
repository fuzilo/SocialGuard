package com.unesp.SocialGuard.dto;

import com.unesp.SocialGuard.domain.InferenceResult;
import jakarta.validation.constraints.NotNull;

public record CommentInferenceRequest(
        @NotNull Long id,
        @NotNull InferenceResult inferenceResult
) {
}
