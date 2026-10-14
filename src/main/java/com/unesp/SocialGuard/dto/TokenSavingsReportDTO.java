package com.unesp.SocialGuard.dto;

import java.math.BigDecimal;

public record TokenSavingsReportDTO(
        long totalTokensConsumed,
        long totalTokensSaved,
        double percentageTokensSaved,
        BigDecimal totalCostUsd,
        BigDecimal totalCostSavedUsd
) {}