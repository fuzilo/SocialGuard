package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.domain.Comment;
import com.unesp.SocialGuard.domain.InferenceResult;
import com.unesp.SocialGuard.dto.BatchClaimResponse;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResultsRequest;
import com.unesp.SocialGuard.dto.BatchResponse;
import com.unesp.SocialGuard.dto.BatchUpdateRequest;
import com.unesp.SocialGuard.dto.CommentRequest;
import com.unesp.SocialGuard.exception.BatchEnqueueException;
import com.unesp.SocialGuard.exception.BatchStateConflictException;
import com.unesp.SocialGuard.exception.ResourceNotFoundException;
import com.unesp.SocialGuard.repository.BatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Regras de negócio do ciclo de vida de um lote.
 *
 * <pre>
 * PENDING ──claim──▶ PROCESSING ──saveResults──▶ COMPLETED
 *                         └──────markFailed────▶ FAILED
 * </pre>
 *
 * Violações dessas transições geram {@link BatchStateConflictException} (HTTP 409).
 */
@Service
@RequiredArgsConstructor
public class BatchService {

    private final BatchRepository batchRepository;
    private final BatchMessagePublisher messagePublisher;

    /**
     * Persiste o lote como {@code PENDING} e só depois publica seu {@code batchId} na fila.
     *
     * @throws BatchEnqueueException se a publicação falhar; o lote permanece salvo como {@code PENDING}
     *                               e pode ser reenfileirado com {@link #enqueue(String)}
     */
    public BatchResponse create(BatchCreateRequest request) {
        Batch batch = new Batch();
        batch.setCompanyId(request.companyId());
        batch.setLlmModel(request.llmModel());
        batch.setStatus(BatchStatus.PENDING);
        request.comments().stream().map(this::toComment).forEach(batch::addComment);
        Batch saved = batchRepository.saveAndFlush(batch);
        publish(saved.getBatchId());
        return BatchResponse.from(saved);
    }

    /**
     * Republica um lote {@code PENDING} na fila (recuperação após falha do broker).
     *
     * @throws BatchStateConflictException se o lote não estiver {@code PENDING}
     */
    public void enqueue(String batchId) {
        Batch batch = getBatch(batchId);
        if (batch.getStatus() != BatchStatus.PENDING) {
            throw new BatchStateConflictException(
                    "Somente lotes PENDING podem ser publicados novamente: " + batchId);
        }
        publish(batchId);
    }

    /**
     * Reivindica atomicamente um lote {@code PENDING}, movendo-o para {@code PROCESSING}.
     * Apenas um consumidor recebe {@code claimed = true}; mensagens duplicadas recebem {@code false}.
     */
    @Transactional
    public BatchClaimResponse claim(String batchId) {
        int claimed = batchRepository.claimIfPending(
                batchId, BatchStatus.PENDING, BatchStatus.PROCESSING, LocalDateTime.now());
        Batch batch = getBatch(batchId);
        return new BatchClaimResponse(claimed == 1, batch.getStatus());
    }

    /**
     * Marca um lote {@code PROCESSING} como {@code FAILED}. Idempotente para lotes já {@code FAILED}.
     */
    @Transactional
    public BatchResponse markFailed(String batchId) {
        Batch batch = getBatch(batchId);
        if (batch.getStatus() == BatchStatus.FAILED) {
            return BatchResponse.from(batch);
        }
        if (batch.getStatus() != BatchStatus.PROCESSING) {
            throw new BatchStateConflictException(
                    "Somente lotes PROCESSING podem ser marcados como FAILED: " + batchId);
        }
        batch.setStatus(BatchStatus.FAILED);
        batch.setCompletedAt(LocalDateTime.now());
        return BatchResponse.from(batchRepository.saveAndFlush(batch));
    }

    /**
     * Grava a classificação de cada comentário e conclui o lote.
     * <p>
     * Os resultados devem conter cada comentário do lote exatamente uma vez. Reenviar os mesmos
     * resultados para um lote já {@code COMPLETED} é idempotente; resultados diferentes geram conflito.
     */
    @Transactional
    public BatchResponse saveResults(String batchId, BatchResultsRequest request) {
        Batch batch = getBatch(batchId);
        Map<Long, InferenceResult> results = request.comments().stream()
                .collect(Collectors.toMap(
                        result -> result.id(),
                        result -> result.inferenceResult(),
                        (first, duplicate) -> {
                            throw new BatchStateConflictException("commentId duplicado nos resultados.");
                        }));
        Map<Long, Comment> commentsById = batch.getComments().stream()
                .collect(Collectors.toMap(Comment::getId, Function.identity()));

        if (!commentsById.keySet().equals(results.keySet())) {
            throw new BatchStateConflictException(
                    "Os resultados devem conter exatamente os comentários do lote.");
        }
        if (batch.getStatus() == BatchStatus.COMPLETED) {
            if (hasSameResults(batch, request, commentsById, results)) {
                return BatchResponse.from(batch);
            }
            throw new BatchStateConflictException("O lote já foi concluído com resultados diferentes.");
        }
        if (batch.getStatus() != BatchStatus.PROCESSING) {
            throw new BatchStateConflictException(
                    "Somente lotes PROCESSING podem receber resultados: " + batchId);
        }

        commentsById.forEach((commentId, comment) -> comment.setInferenceResult(results.get(commentId)));
        if (request.tokensConsumed() != null) {
            batch.setTokensConsumed(request.tokensConsumed());
        }
        if (request.llmModel() != null) {
            batch.setLlmModel(request.llmModel());
        }
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setCompletedAt(LocalDateTime.now());
        return BatchResponse.from(batchRepository.saveAndFlush(batch));
    }

    @Transactional(readOnly = true)
    public BatchResponse findById(String batchId) {
        return BatchResponse.from(getBatch(batchId));
    }

    @Transactional(readOnly = true)
    public List<BatchResponse> findAll(String companyId, BatchStatus status) {
        List<Batch> batches;
        if (companyId != null && status != null) {
            batches = batchRepository.findByCompanyIdAndStatus(companyId, status);
        } else if (companyId != null) {
            batches = batchRepository.findByCompanyId(companyId);
        } else if (status != null) {
            batches = batchRepository.findByStatus(status);
        } else {
            batches = batchRepository.findAll();
        }
        return batches.stream().map(BatchResponse::from).toList();
    }

    /**
     * Atualização administrativa, sem validação de transição; campos nulos são ignorados.
     * Usada, por exemplo, para devolver a {@code PENDING} um lote abandonado em {@code PROCESSING}.
     */
    @Transactional
    public BatchResponse update(String batchId, BatchUpdateRequest request) {
        Batch batch = getBatch(batchId);
        if (request.status() != null) {
            batch.setStatus(request.status());
            batch.setCompletedAt(isFinal(request.status()) ? LocalDateTime.now() : null);
        }
        if (request.tokensConsumed() != null) {
            batch.setTokensConsumed(request.tokensConsumed());
        }
        if (request.llmModel() != null) {
            batch.setLlmModel(request.llmModel());
        }
        if (request.comments() != null) {
            batch.replaceComments(request.comments().stream().map(this::toComment).toList());
        }
        return BatchResponse.from(batchRepository.saveAndFlush(batch));
    }

    @Transactional
    public void delete(String batchId) {
        batchRepository.delete(getBatch(batchId));
    }

    private Batch getBatch(String batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch não encontrado: " + batchId));
    }

    private boolean isFinal(BatchStatus status) {
        return status == BatchStatus.COMPLETED || status == BatchStatus.FAILED;
    }

    private boolean hasSameResults(
            Batch batch,
            BatchResultsRequest request,
            Map<Long, Comment> commentsById,
            Map<Long, InferenceResult> results) {
        if (request.tokensConsumed() != null && request.tokensConsumed() != batch.getTokensConsumed()) {
            return false;
        }
        if (request.llmModel() != null && !request.llmModel().equals(batch.getLlmModel())) {
            return false;
        }
        return commentsById.entrySet().stream()
                .allMatch(entry -> results.get(entry.getKey()) == entry.getValue().getInferenceResult());
    }

    private void publish(String batchId) {
        try {
            messagePublisher.publish(batchId);
        } catch (RuntimeException ex) {
            throw new BatchEnqueueException(batchId, ex);
        }
    }

    private Comment toComment(CommentRequest request) {
        Comment comment = new Comment();
        comment.setPostId(request.postId());
        comment.setCommentId(request.commentId());
        comment.setName(request.name());
        comment.setEmail(request.email());
        comment.setBody(request.body());
        return comment;
    }
}
