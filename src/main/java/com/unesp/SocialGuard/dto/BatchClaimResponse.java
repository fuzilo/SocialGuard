package com.unesp.SocialGuard.dto;

import com.unesp.SocialGuard.domain.BatchStatus;

public record BatchClaimResponse(boolean claimed, BatchStatus status) {
}
