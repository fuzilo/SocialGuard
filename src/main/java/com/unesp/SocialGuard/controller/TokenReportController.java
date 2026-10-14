package com.unesp.SocialGuard.controller;

import com.unesp.SocialGuard.domain.TokenUsageReport;
import com.unesp.SocialGuard.dto.TokenRecordRequest;
import com.unesp.SocialGuard.dto.TokenSavingsReportDTO;
import com.unesp.SocialGuard.dto.TokenSummaryResponse;
import com.unesp.SocialGuard.service.TokenManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
public class TokenReportController {

    private final TokenManagementService tokenManagementService;

    @PostMapping("/reports")
    public ResponseEntity<TokenUsageReport> recordUsage(@Valid @RequestBody TokenRecordRequest request) {
        TokenUsageReport savedReport = tokenManagementService.recordAndOptimizeTokenUsage(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedReport);
    }

    @GetMapping("/reports/savings")
    public ResponseEntity<TokenSavingsReportDTO> getSavingsReport() {
        return ResponseEntity.ok(tokenManagementService.getSavingsReport());
    }

    @GetMapping("/reports/company/{companyId}")
    public ResponseEntity<TokenSummaryResponse> getCompanySummary(@PathVariable String companyId) {
        return ResponseEntity.ok(tokenManagementService.getCompanySummary(companyId));
    }

    @GetMapping("/reports/filter")
    public ResponseEntity<List<TokenUsageReport>> getReportsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(tokenManagementService.findByDateRange(start, end));
    }
}