package com.unesp.SocialGuard.controller;

import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.domain.InferenceResult;
import com.unesp.SocialGuard.dto.BatchClaimResponse;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResponse;
import com.unesp.SocialGuard.dto.BatchResultsRequest;
import com.unesp.SocialGuard.dto.BatchUpdateRequest;
import com.unesp.SocialGuard.dto.CommentResponse;
import com.unesp.SocialGuard.exception.BatchEnqueueException;
import com.unesp.SocialGuard.exception.BatchStateConflictException;
import com.unesp.SocialGuard.exception.ResourceNotFoundException;
import com.unesp.SocialGuard.service.BatchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BatchController.class)
class BatchControllerTest {

    private static final String BASE = "/api/v1/batches";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchService batchService;

    // --- POST /batches ---

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        when(batchService.create(any(BatchCreateRequest.class))).thenReturn(response(BatchStatus.PENDING));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "companyId": "company-1",
                          "llmModel": "model-x",
                          "comments": [ { "postId": "p1", "commentId": "c1", "body": "hello" } ]
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(BASE + "/batch-1")))
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<BatchCreateRequest> captor = ArgumentCaptor.forClass(BatchCreateRequest.class);
        verify(batchService).create(captor.capture());
        assertEquals("company-1", captor.getValue().companyId());
        assertEquals("hello", captor.getValue().comments().getFirst().body());
    }

    @Test
    void createWithoutCompanyIdReturns400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        { "comments": [ { "body": "hello" } ] }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("companyId")));

        verifyNoInteractions(batchService);
    }

    @Test
    void createWithEmptyCommentsReturns400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        { "companyId": "company-1", "comments": [] }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("comments")));

        verifyNoInteractions(batchService);
    }

    @Test
    void createWithBlankCommentBodyReturns400() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        { "companyId": "company-1", "comments": [ { "body": " " } ] }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("body")));

        verifyNoInteractions(batchService);
    }

    @Test
    void createReturns503WithBatchIdWhenBrokerFails() throws Exception {
        when(batchService.create(any(BatchCreateRequest.class)))
                .thenThrow(new BatchEnqueueException("batch-1", new IllegalStateException("down")));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        { "companyId": "company-1", "comments": [ { "body": "hello" } ] }
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.batchId").value("batch-1"));
    }

    // --- POST /batches/{id}/enqueue ---

    @Test
    void enqueueReturns202() throws Exception {
        mockMvc.perform(post(BASE + "/batch-1/enqueue"))
                .andExpect(status().isAccepted());

        verify(batchService).enqueue("batch-1");
    }

    @Test
    void enqueueNonPendingBatchReturns409() throws Exception {
        doThrow(new BatchStateConflictException("not pending")).when(batchService).enqueue("batch-1");

        mockMvc.perform(post(BASE + "/batch-1/enqueue"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("not pending"));
    }

    // --- POST /batches/{id}/claim ---

    @Test
    void claimReturnsClaimResult() throws Exception {
        when(batchService.claim("batch-1")).thenReturn(new BatchClaimResponse(true, BatchStatus.PROCESSING));

        mockMvc.perform(post(BASE + "/batch-1/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    // --- PUT /batches/{id}/results ---

    @Test
    void saveResultsReturnsCompletedBatch() throws Exception {
        when(batchService.saveResults(eq("batch-1"), any(BatchResultsRequest.class)))
                .thenReturn(response(BatchStatus.COMPLETED));

        mockMvc.perform(put(BASE + "/batch-1/results").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "comments": [ { "id": 42, "inferenceResult": "SPAM" } ],
                          "tokensConsumed": 1500,
                          "llmModel": "gpt-4"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        ArgumentCaptor<BatchResultsRequest> captor = ArgumentCaptor.forClass(BatchResultsRequest.class);
        verify(batchService).saveResults(eq("batch-1"), captor.capture());
        assertEquals(InferenceResult.SPAM, captor.getValue().comments().getFirst().inferenceResult());
        assertEquals(1500, captor.getValue().tokensConsumed());
    }

    @Test
    void saveResultsWithNegativeTokensReturns400() throws Exception {
        mockMvc.perform(put(BASE + "/batch-1/results").contentType(MediaType.APPLICATION_JSON).content("""
                        { "comments": [ { "id": 42, "inferenceResult": "OK" } ], "tokensConsumed": -1 }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("tokensConsumed")));

        verifyNoInteractions(batchService);
    }

    @Test
    void saveResultsWithoutInferenceReturns400() throws Exception {
        mockMvc.perform(put(BASE + "/batch-1/results").contentType(MediaType.APPLICATION_JSON).content("""
                        { "comments": [ { "id": 42 } ] }
                        """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(batchService);
    }

    @Test
    void saveResultsConflictReturns409() throws Exception {
        when(batchService.saveResults(eq("batch-1"), any(BatchResultsRequest.class)))
                .thenThrow(new BatchStateConflictException("O lote já foi concluído com resultados diferentes."));

        mockMvc.perform(put(BASE + "/batch-1/results").contentType(MediaType.APPLICATION_JSON).content("""
                        { "comments": [ { "id": 42, "inferenceResult": "OK" } ] }
                        """))
                .andExpect(status().isConflict());
    }

    // --- POST /batches/{id}/failure ---

    @Test
    void markFailedReturnsFailedBatch() throws Exception {
        when(batchService.markFailed("batch-1")).thenReturn(response(BatchStatus.FAILED));

        mockMvc.perform(post(BASE + "/batch-1/failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    // --- GET ---

    @Test
    void findAllPassesFiltersToService() throws Exception {
        when(batchService.findAll("company-1", BatchStatus.COMPLETED))
                .thenReturn(List.of(response(BatchStatus.COMPLETED)));

        mockMvc.perform(get(BASE).param("companyId", "company-1").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].batchId").value("batch-1"));
    }

    @Test
    void findAllWithoutFiltersPassesNulls() throws Exception {
        when(batchService.findAll(null, null)).thenReturn(List.of());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void findByIdReturnsBatchWithComments() throws Exception {
        when(batchService.findById("batch-1")).thenReturn(response(BatchStatus.PENDING));

        mockMvc.perform(get(BASE + "/batch-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value("company-1"))
                .andExpect(jsonPath("$.comments[0].id").value(42))
                .andExpect(jsonPath("$.comments[0].body").value("hello"));
    }

    @Test
    void findByIdUnknownBatchReturns404() throws Exception {
        when(batchService.findById("missing")).thenThrow(new ResourceNotFoundException("Batch não encontrado: missing"));

        mockMvc.perform(get(BASE + "/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Batch não encontrado: missing"));
    }

    // --- PUT /batches/{id} ---

    @Test
    void updateReturnsUpdatedBatch() throws Exception {
        when(batchService.update(eq("batch-1"), any(BatchUpdateRequest.class)))
                .thenReturn(response(BatchStatus.PENDING));

        mockMvc.perform(put(BASE + "/batch-1").contentType(MediaType.APPLICATION_JSON).content("""
                        { "status": "PENDING" }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<BatchUpdateRequest> captor = ArgumentCaptor.forClass(BatchUpdateRequest.class);
        verify(batchService).update(eq("batch-1"), captor.capture());
        assertEquals(BatchStatus.PENDING, captor.getValue().status());
    }

    // --- DELETE /batches/{id} ---

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete(BASE + "/batch-1"))
                .andExpect(status().isNoContent());

        verify(batchService).delete("batch-1");
    }

    @Test
    void deleteUnknownBatchReturns404() throws Exception {
        doThrow(new ResourceNotFoundException("Batch não encontrado: missing")).when(batchService).delete("missing");

        mockMvc.perform(delete(BASE + "/missing"))
                .andExpect(status().isNotFound());
    }

    private BatchResponse response(BatchStatus status) {
        CommentResponse comment = new CommentResponse(42L, "p1", "c1", null, null, "hello", null, null, null);
        return new BatchResponse("batch-1", "company-1", null, null, null, status, 0, "model-x", List.of(comment));
    }
}
