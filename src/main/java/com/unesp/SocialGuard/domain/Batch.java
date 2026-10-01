package com.unesp.SocialGuard.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "batch")
@Getter
@Setter
@NoArgsConstructor
public class Batch {

    @Id
    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "tokens_consumed")
    private int tokensConsumed;

    @Column(name = "llm_model")
    private String llmModel;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    public void addComment(Comment comment) {
        comment.setBatch(this);
        comments.add(comment);
    }

    public void replaceComments(List<Comment> newComments) {
        comments.clear();
        newComments.forEach(this::addComment);
    }

    @PrePersist
    void onCreate() {
        if (batchId == null) {
            batchId = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = BatchStatus.PENDENTE;
        }
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
