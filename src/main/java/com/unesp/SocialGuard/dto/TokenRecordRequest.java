package com.unesp.SocialGuard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TokenRecordRequest(
        @NotBlank String batchId,
        @NotBlank String companyId,
        @NotBlank String llmModel,
        @NotNull Integer promptTokens,
        @NotNull Integer completionTokens,
        @NotNull Integer baselineTokens
) {}