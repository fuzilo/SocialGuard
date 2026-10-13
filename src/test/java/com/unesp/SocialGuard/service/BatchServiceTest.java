package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.domain.Comment;
import com.unesp.SocialGuard.domain.InferenceResult;
import com.unesp.SocialGuard.dto.BatchClaimResponse;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResponse;
import com.unesp.SocialGuard.dto.BatchResultsRequest;
import com.unesp.SocialGuard.dto.BatchUpdateRequest;
import com.unesp.SocialGuard.dto.CommentInferenceRequest;
import com.unesp.SocialGuard.dto.CommentRequest;
import com.unesp.SocialGuard.exception.BatchEnqueueException;
import com.unesp.SocialGuard.exception.BatchStateConflictException;
import com.unesp.SocialGuard.exception.ResourceNotFoundException;
import com.unesp.SocialGuard.repository.BatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchServiceTest {

    @Mock
    private BatchRepository batchRepository;

    @Mock
    private BatchMessagePublisher messagePublisher;

    @InjectMocks
    private BatchService batchService;

    // --- create ---

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
    void createMapsRequestIntoPendingBatchWithLinkedComments() {
        when(batchRepository.saveAndFlush(any(Batch.class))).thenAnswer(invocation -> invocation.getArgument(0));

        batchService.create(createRequest());

        ArgumentCaptor<Batch> captor = ArgumentCaptor.forClass(Batch.class);
        verify(batchRepository).saveAndFlush(captor.capture());
        Batch saved = captor.getValue();
        assertEquals("company-1", saved.getCompanyId());
        assertEquals("model-x", saved.getLlmModel());
        assertEquals(BatchStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getComments().size());
        Comment comment = saved.getComments().getFirst();
        assertSame(saved, comment.getBatch());
        assertEquals("post-1", comment.getPostId());
        assertEquals("comment-1", comment.getCommentId());
        assertEquals("User", comment.getName());
        assertEquals("user@example.com", comment.getEmail());
        assertEquals("Original post", comment.getBody());
        assertNull(comment.getInferenceResult());
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
        assertEquals("broker unavailable", exception.getCause().getMessage());
        verify(batchRepository).saveAndFlush(any(Batch.class));
    }

    // --- enqueue ---

    @Test
    void enqueuePublishesPendingBatchAgain() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.PENDING)));

        batchService.enqueue("batch-1");

        verify(messagePublisher).publish("batch-1");
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void enqueueRejectsBatchThatIsNotPending(BatchStatus status) {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(status)));

        assertThrows(BatchStateConflictException.class, () -> batchService.enqueue("batch-1"));

        verifyNoInteractions(messagePublisher);
    }

    @Test
    void enqueueWrapsPublishFailure() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.PENDING)));
        doThrow(new IllegalStateException("timeout")).when(messagePublisher).publish("batch-1");

        BatchEnqueueException exception = assertThrows(
                BatchEnqueueException.class, () -> batchService.enqueue("batch-1"));

        assertEquals("batch-1", exception.getBatchId());
    }

    @Test
    void enqueueUnknownBatchThrowsNotFound() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> batchService.enqueue("missing"));

        verifyNoInteractions(messagePublisher);
    }

    // --- claim ---

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
    void claimUnknownBatchThrowsNotFound() {
        when(batchRepository.claimIfPending(eq("missing"), any(), any(), any())).thenReturn(0);
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> batchService.claim("missing"));
    }

    // --- saveResults ---

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
        assertEquals("model-x", response.llmModel());
        assertNotNull(response.completedAt());
        verify(batchRepository).saveAndFlush(batch);
    }

    @Test
    void savingResultsWithoutOptionalFieldsKeepsCurrentValues() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        batch.setTokensConsumed(50);
        batch.setLlmModel("original-model");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.OK)), null, null);

        var response = batchService.saveResults("batch-1", request);

        assertEquals(50, response.tokensConsumed());
        assertEquals("original-model", response.llmModel());
        assertEquals(BatchStatus.COMPLETED, response.status());
    }

    @Test
    void repeatingIdenticalResultsAfterCompletionIsIdempotent() {
        Batch batch = completedBatch();
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.SPAM)), 120, "model-x");

        var response = batchService.saveResults("batch-1", request);

        assertEquals(BatchStatus.COMPLETED, response.status());
        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    @Test
    void differentInferenceAfterCompletionIsConflict() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(completedBatch()));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.VIOLENT)), 120, "model-x");

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));

        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    @Test
    void differentTokensAfterCompletionIsConflict() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(completedBatch()));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.SPAM)), 999, "model-x");

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));
    }

    @Test
    void differentModelAfterCompletionIsConflict() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(completedBatch()));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.SPAM)), 120, "other-model");

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"PENDING", "FAILED"})
    void savingResultsRequiresProcessingBatch(BatchStatus status) {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(status)));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.OK)), 10, null);

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));

        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    @Test
    void duplicatedCommentIdInResultsIsConflict() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.PROCESSING)));
        BatchResultsRequest request = new BatchResultsRequest(List.of(
                new CommentInferenceRequest(42L, InferenceResult.OK),
                new CommentInferenceRequest(42L, InferenceResult.SPAM)), 10, null);

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));
    }

    @Test
    void resultsMissingABatchCommentIsConflict() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        Comment other = new Comment();
        other.setId(43L);
        batch.addComment(other);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        BatchResultsRequest request = new BatchResultsRequest(
                List.of(new CommentInferenceRequest(42L, InferenceResult.OK)), 10, null);

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));

        assertNull(batch.getComments().getFirst().getInferenceResult());
    }

    @Test
    void resultsWithUnknownCommentIsConflict() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.PROCESSING)));
        BatchResultsRequest request = new BatchResultsRequest(List.of(
                new CommentInferenceRequest(42L, InferenceResult.OK),
                new CommentInferenceRequest(99L, InferenceResult.OK)), 10, null);

        assertThrows(BatchStateConflictException.class, () -> batchService.saveResults("batch-1", request));
    }

    // --- markFailed ---

    @Test
    void processingBatchCanBeMarkedFailed() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);

        var response = batchService.markFailed("batch-1");

        assertEquals(BatchStatus.FAILED, response.status());
        assertNotNull(response.completedAt());
        verify(batchRepository).saveAndFlush(batch);
    }

    @Test
    void markingFailedBatchAgainIsIdempotent() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.FAILED)));

        var response = batchService.markFailed("batch-1");

        assertEquals(BatchStatus.FAILED, response.status());
        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"PENDING", "COMPLETED"})
    void markFailedRejectsBatchThatIsNotProcessing(BatchStatus status) {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(status)));

        assertThrows(BatchStateConflictException.class, () -> batchService.markFailed("batch-1"));

        verify(batchRepository, never()).saveAndFlush(any(Batch.class));
    }

    // --- findById / findAll ---

    @Test
    void findByIdReturnsMappedBatch() {
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batchWithComment(BatchStatus.PENDING)));

        BatchResponse response = batchService.findById("batch-1");

        assertEquals("batch-1", response.batchId());
        assertEquals("company-1", response.companyId());
        assertEquals(42L, response.comments().getFirst().id());
    }

    @Test
    void findByIdUnknownBatchThrowsNotFound() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class, () -> batchService.findById("missing"));

        assertTrue(exception.getMessage().contains("missing"));
    }

    @Test
    void findAllWithoutFiltersReturnsEveryBatch() {
        when(batchRepository.findAll()).thenReturn(List.of(batchWithComment(BatchStatus.PENDING)));

        assertEquals(1, batchService.findAll(null, null).size());
    }

    @Test
    void findAllFiltersByCompany() {
        when(batchRepository.findByCompanyId("company-1")).thenReturn(List.of(batchWithComment(BatchStatus.PENDING)));

        assertEquals(1, batchService.findAll("company-1", null).size());

        verify(batchRepository, never()).findAll();
    }

    @Test
    void findAllFiltersByStatus() {
        when(batchRepository.findByStatus(BatchStatus.FAILED)).thenReturn(List.of());

        assertTrue(batchService.findAll(null, BatchStatus.FAILED).isEmpty());

        verify(batchRepository, never()).findAll();
    }

    @Test
    void findAllFiltersByCompanyAndStatus() {
        when(batchRepository.findByCompanyIdAndStatus("company-1", BatchStatus.COMPLETED))
                .thenReturn(List.of(completedBatch()));

        List<BatchResponse> responses = batchService.findAll("company-1", BatchStatus.COMPLETED);

        assertEquals(BatchStatus.COMPLETED, responses.getFirst().status());
    }

    // --- update ---

    @Test
    void updateIgnoresNullFields() {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        batch.setTokensConsumed(10);
        batch.setLlmModel("model-x");
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);

        var response = batchService.update("batch-1", new BatchUpdateRequest(null, null, null, null));

        assertEquals(BatchStatus.PROCESSING, response.status());
        assertEquals(10, response.tokensConsumed());
        assertEquals("model-x", response.llmModel());
        assertEquals(1, response.comments().size());
    }

    @ParameterizedTest
    @EnumSource(value = BatchStatus.class, names = {"COMPLETED", "FAILED"})
    void updateToFinalStatusSetsCompletedAt(BatchStatus status) {
        Batch batch = batchWithComment(BatchStatus.PROCESSING);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);

        var response = batchService.update("batch-1", new BatchUpdateRequest(status, null, null, null));

        assertEquals(status, response.status());
        assertNotNull(response.completedAt());
    }

    @Test
    void updateBackToPendingClearsCompletedAt() {
        Batch batch = batchWithComment(BatchStatus.FAILED);
        batch.setCompletedAt(LocalDateTime.now());
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);

        var response = batchService.update("batch-1",
                new BatchUpdateRequest(BatchStatus.PENDING, null, null, null));

        assertEquals(BatchStatus.PENDING, response.status());
        assertNull(response.completedAt());
    }

    @Test
    void updateReplacesCommentsAndOptionalFields() {
        Batch batch = batchWithComment(BatchStatus.PENDING);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));
        when(batchRepository.saveAndFlush(batch)).thenReturn(batch);
        BatchUpdateRequest request = new BatchUpdateRequest(null, 300, "model-y", List.of(
                new CommentRequest("post-2", "c-2", "A", "a@example.com", "first"),
                new CommentRequest("post-2", "c-3", "B", "b@example.com", "second")));

        var response = batchService.update("batch-1", request);

        assertEquals(300, response.tokensConsumed());
        assertEquals("model-y", response.llmModel());
        assertEquals(List.of("first", "second"),
                response.comments().stream().map(c -> c.body()).toList());
        assertTrue(batch.getComments().stream().allMatch(c -> c.getBatch() == batch));
    }

    @Test
    void updateUnknownBatchThrowsNotFound() {
        when(batchRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> batchService.update("missing", new BatchUpdateRequest(null, null, null, null)));
    }

    // --- delete ---

    @Test
    void deleteRemovesExistingBatch() {
        Batch batch = batchWithComment(BatchStatus.COMPLETED);
        when(batchRepository.findById("batch-1")).thenReturn(Optional.of(batch));

        batchService.delete("batch-1");

        verify(batchRepository).delete(batch);
    }

    @Test
    void deleteUnknownBatchThrowsNotFound() {
        when(batchRepository.findById(anyString())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> batchService.delete("missing"));

        verify(batchRepository, never()).delete(any(Batch.class));
    }

    private BatchCreateRequest createRequest() {
        return new BatchCreateRequest("company-1", "model-x",
                List.of(new CommentRequest("post-1", "comment-1", "User", "user@example.com", "Original post")));
    }

    private Batch completedBatch() {
        Batch batch = batchWithComment(BatchStatus.COMPLETED);
        batch.setTokensConsumed(120);
        batch.setLlmModel("model-x");
        batch.getComments().getFirst().setInferenceResult(InferenceResult.SPAM);
        return batch;
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
