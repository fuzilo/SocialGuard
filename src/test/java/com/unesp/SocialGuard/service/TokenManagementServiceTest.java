package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.TokenUsageReport;
import com.unesp.SocialGuard.dto.TokenRecordRequest;
import com.unesp.SocialGuard.repository.TokenUsageReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenManagementServiceTest {

    @Mock
    private TokenUsageReportRepository reportRepository;

    @InjectMocks
    private TokenManagementService tokenManagementService;

    @Test
    @DisplayName("Deve calcular economia e persistir o relatorio de uso de tokens com sucesso")
    void shouldRecordAndOptimizeTokenUsage() {
        TokenRecordRequest request = new TokenRecordRequest(
                "batch-uuid-1", "company-1", "gpt-4o-mini", 100, 50, 200
        );

        when(reportRepository.save(any(TokenUsageReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TokenUsageReport result = tokenManagementService.recordAndOptimizeTokenUsage(request);

        assertNotNull(result);
        assertEquals(150, result.getTotalTokens());
        assertEquals(50, result.getTokensSaved());
        verify(reportRepository, times(1)).save(any(TokenUsageReport.class));
    }
}