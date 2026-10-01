package com.unesp.SocialGuard.dto;

import com.unesp.SocialGuard.domain.BatchStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Campos nulos são ignorados (atualização parcial).
 * Se {@code comments} for informado, substitui a lista de comentários do lote.
 */
public record BatchUpdateRequest(
        BatchStatus status,
        @PositiveOrZero Integer tokensConsumed,
        String llmModel,
        List<@Valid CommentRequest> comments
) {
}
