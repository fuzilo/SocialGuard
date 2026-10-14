package com.unesp.SocialGuard.repository;

import com.unesp.SocialGuard.domain.TokenUsageReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TokenUsageReportRepository extends JpaRepository<TokenUsageReport, Long> {

    List<TokenUsageReport> findByCompanyId(String companyId);

    List<TokenUsageReport> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

    @Query("SELECT COALESCE(SUM(r.totalTokens), 0) FROM TokenUsageReport r WHERE r.companyId = :companyId")
    Long sumTotalTokensByCompany(@Param("companyId") String companyId);

    @Query("""
        SELECT 
            COALESCE(SUM(r.totalTokens), 0) as totalTokensConsumed,
            COALESCE(SUM(r.tokensSaved), 0) as totalTokensSaved,
            COALESCE(SUM(r.estimatedCostUsd), 0) as totalCostUsd,
            COALESCE(SUM(r.costSavedUsd), 0) as totalCostSavedUsd
        FROM TokenUsageReport r
    """)
    TokenSavingsSummaryProjection getGlobalSavingsSummary();

    interface TokenSavingsSummaryProjection {
        Long getTotalTokensConsumed();
        Long getTotalTokensSaved();
        BigDecimal getTotalCostUsd();
        BigDecimal getTotalCostSavedUsd();
    }
}