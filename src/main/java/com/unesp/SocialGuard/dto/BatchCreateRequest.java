package com.unesp.SocialGuard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchCreateRequest(
        @NotBlank String companyId,
        String llmModel,
        @NotEmpty List<@Valid CommentRequest> comments
) {
}
