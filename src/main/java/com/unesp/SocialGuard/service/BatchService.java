package com.unesp.SocialGuard.service;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import com.unesp.SocialGuard.domain.Comment;
import com.unesp.SocialGuard.dto.BatchCreateRequest;
import com.unesp.SocialGuard.dto.BatchResponse;
import com.unesp.SocialGuard.dto.BatchUpdateRequest;
import com.unesp.SocialGuard.dto.CommentRequest;
import com.unesp.SocialGuard.exception.ResourceNotFoundException;
import com.unesp.SocialGuard.repository.BatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BatchService {

    private final BatchRepository batchRepository;

    @Transactional
    public BatchResponse create(BatchCreateRequest request) {
        Batch batch = new Batch();
        batch.setCompanyId(request.companyId());
        batch.setLlmModel(request.llmModel());
        batch.setStatus(BatchStatus.PENDENTE);
        request.comments().stream().map(this::toComment).forEach(batch::addComment);
        return BatchResponse.from(batchRepository.save(batch));
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
        return status == BatchStatus.CONCLUIDO || status == BatchStatus.FALHOU;
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
