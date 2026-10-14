package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.TokenUsageReport;
import com.unesp.SocialGuard.dto.TokenRecordRequest;
import com.unesp.SocialGuard.dto.TokenSavingsReportDTO;
import com.unesp.SocialGuard.dto.TokenSummaryResponse;
import com.unesp.SocialGuard.repository.TokenUsageReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TokenManagementService {

    private final TokenUsageReportRepository reportRepository;
    private static final BigDecimal COST_PER_1K_TOKENS_DEFAULT = new BigDecimal("0.002000");

    @Transactional
    public TokenUsageReport recordAndOptimizeTokenUsage(TokenRecordRequest request) {
        int totalTokens = request.promptTokens() + request.completionTokens();
        int baseline = Math.max(request.baselineTokens(), totalTokens);
        int tokensSaved = baseline - totalTokens;

        BigDecimal costPerToken = COST_PER_1K_TOKENS_DEFAULT.divide(new BigDecimal("1000"), 8, RoundingMode.HALF_UP);
        BigDecimal estimatedCost = costPerToken.multiply(new BigDecimal(totalTokens));
        BigDecimal costSaved = costPerToken.multiply(new BigDecimal(tokensSaved));

        TokenUsageReport report = TokenUsageReport.builder()
                .batchId(request.batchId())
                .companyId(request.companyId())
                .llmModel(request.llmModel())
                .promptTokens(request.promptTokens())
                .completionTokens(request.completionTokens())
                .totalTokens(totalTokens)
                .baselineTokens(baseline)
                .tokensSaved(tokensSaved)
                .estimatedCostUsd(estimatedCost)
                .costSavedUsd(costSaved)
                .build();

        return reportRepository.save(report);
    }

    @Transactional(readOnly = true)
    public TokenSummaryResponse getCompanySummary(String companyId) {
        List<TokenUsageReport> reports = reportRepository.findByCompanyId(companyId);
        long totalTokens = reportRepository.sumTotalTokensByCompany(companyId);

        BigDecimal totalCost = reports.stream()
                .map(TokenUsageReport::getEstimatedCostUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TokenSummaryResponse(companyId, totalTokens, totalCost, reports.size());
    }

    @Transactional(readOnly = true)
    public TokenSavingsReportDTO getSavingsReport() {
        var summary = reportRepository.getGlobalSavingsSummary();

        long consumed = summary.getTotalTokensConsumed() != null ? summary.getTotalTokensConsumed() : 0L;
        long saved = summary.getTotalTokensSaved() != null ? summary.getTotalTokensSaved() : 0L;
        long totalWithoutOptimization = consumed + saved;

        double percentageSaved = totalWithoutOptimization > 0
                ? ((double) saved / totalWithoutOptimization) * 100.0
                : 0.0;

        return new TokenSavingsReportDTO(
                consumed,
                saved,
                Math.round(percentageSaved * 100.0) / 100.0,
                summary.getTotalCostUsd() != null ? summary.getTotalCostUsd() : BigDecimal.ZERO,
                summary.getTotalCostSavedUsd() != null ? summary.getTotalCostSavedUsd() : BigDecimal.ZERO
        );
    }

    @Transactional(readOnly = true)
    public List<TokenUsageReport> findByDateRange(LocalDateTime start, LocalDateTime end) {
        return reportRepository.findByCreatedAtBetween(start, end);
    }
}