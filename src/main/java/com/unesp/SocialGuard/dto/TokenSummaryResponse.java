package com.unesp.SocialGuard.dto;

import java.math.BigDecimal;

public record TokenSummaryResponse(
        String companyId,
        long totalTokensConsumed,
        BigDecimal totalCostUsd,
        long totalReportsRegistered
) {}