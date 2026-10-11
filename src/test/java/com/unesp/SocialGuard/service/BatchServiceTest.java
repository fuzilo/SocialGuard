package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.domain.Comment;
import com.unesp.SocialGuard.domain.InferenceResult;
import com.unesp.SocialGuard.dto.BatchClaimResponse;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResultsRequest;
import com.unesp.SocialGuard.dto.CommentInferenceRequest;
import com.unesp.SocialGuard.dto.CommentRequest;
import com.unesp.SocialGuard.exception.BatchEnqueueException;
import com.unesp.SocialGuard.repository.BatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class BatchServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private BatchMessagePublisher messagePublisher;

    @InjectMocks
    private BatchService batchService;

    @Test
    void createPersistsBatchBeforePublishingOnlyItsId() {
        when(batchRepository.saveAndFlush(any(Batch.class))).thenAnswer(invocation -> {
            Batch batch = invocation.getArgument(0);
            batch.setBatchId("batch-1");
            return batch;
        });

        var response = batchService.create(createRequest());

        assertEquals("batch-1", response.batchId());
        InOrder order = inOrder(batchRepository, messagePublisher);
        order.verify(batchRepository).saveAndFlush(any(Batch.class));
        order.verify(messagePublisher).publish("batch-1");
    }

    @Test
    void createReportsPublishFailureWithPersistedBatchId() {
        when(batchRepository.saveAndFlush(any(Batch.class))).thenAnswer(invocation -> {
            Batch batch = invocation.getArgument(0);
            batch.setBatchId("batch-1");
            return batch;
        });
        doThrow(new IllegalStateException("broker unavailable"))
                .when(messagePublisher).publish("batch-1");

        BatchEnqueueException exception = assertThrows(
                BatchEnqueueException.class, () -> batchService.create(createRequest()));

        assertEquals("batch-1", exception.getBatchId());
        verify(batchRepository).saveAndFlush(any(Batch.class));
    }

    @Test
    void duplicateClaimDoesNotClaimBatchAgain() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.claimIfPending(
                eq("batch-1"),
                eq(BatchStatus.PENDING),
                eq(BatchStatus.PROCESSING),
                any()))
                .thenReturn(0);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        BatchClaimResponse response = batchService.claim("batch-1");

        assertFalse(response.claimed());
        assertEquals(BatchStatus.PROCESSING, response.status());
    }

    @Test
    void pendingBatchCanBeClaimedOnce() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.claimIfPending(
                eq("batch-1"), eq(BatchStatus.PENDING), eq(BatchStatus.PROCESSING), any()))
                .thenReturn(1);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        BatchClaimResponse response = batchService.claim("batch-1");

        assertTrue(response.claimed());
        assertEquals(BatchStatus.PROCESSING, response.status());
    }

    @Test
    void savingResultsPreservesCommentAndCompletesBatch() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.SPAM)), 120, "model-x");

        var response = batchService.saveResults("batch-1", request);

        assertEquals(BatchStatus.COMPLETED, response.status());
        assertEquals(InferenceResult.SPAM, response.comments().getFirst().inferenceResult());
        assertEquals("Original post", response.comments().getFirst().body());
        assertEquals(120, response.tokensConsumed());
        assertTrue(response.completedAt() != null);
        verify(batchRepository).saveAndFlush(batch);
    }

    @Test
    void repeatingIdenticalResultsAfterCompletionIsIdempotent() {
        Batch batch = batchWithComment(BatchStatus.COMPLETED);
        batch.setTokensConsumed(120);
        batch.setLlmModel("model-x");
        batch.getComments().getFirst().setInferenceResult(InferenceResult.SPAM);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.SPAM)), 120, "model-x");

        var response = batchService.saveResults("batch-1", request);

        assertEquals(BatchStatus.COMPLETED, response.status());
        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    @Test
    void processingBatchCanBeMarkedFailed() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);

        var response = batchService.markFailed("batch-1");

        assertEquals(BatchStatus.FAILED, response.status());
        assertTrue(response.completedAt() != null);
        verify(batchRepository).saveAndFlush(batch);
    }

    private BatchCreateRequest createRequest() {
        return new BatchCreateRequest("company-1", "model-x",
                List.of(new CommentRequest("post-1", "comment-1", "User", "user@example.com", "Original post")));
    }

    private Batch batchWithComment(BatchStatus status) {
        Batch batch = new Batch();
        batch.setBatchId("batch-1");
        batch.setCompanyId("company-1");
        batch.setStatus(status);
        Comment comment = new Comment();
        comment.setId(42L);
        comment.setBody("Original post");
        batch.addComment(comment);
        return batch;
    }
}
