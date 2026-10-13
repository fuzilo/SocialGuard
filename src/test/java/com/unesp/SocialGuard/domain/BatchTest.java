package com.unesp.SocialGuard.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchTest {

    @Test
    void addCommentLinksCommentToBatch() {
        Batch batch = new Batch();
        Comment comment = new Comment();

        batch.addComment(comment);

        assertSame(batch, comment.getBatch());
        assertEquals(List.of(comment), batch.getComments());
    }

    @Test
    void replaceCommentsKeepsSameListInstanceForOrphanRemoval() {
        Batch batch = new Batch();
        batch.addComment(new Comment());
        List<Comment> managedList = batch.getComments();
        Comment replacement = new Comment();

        batch.replaceComments(List.of(replacement));

        assertSame(managedList, batch.getComments());
        assertEquals(List.of(replacement), batch.getComments());
        assertSame(batch, replacement.getBatch());
    }

    @Test
    void onCreateGeneratesIdDefaultStatusAndTimestamps() {
        Batch batch = new Batch();

        batch.onCreate();

        assertNotNull(batch.getBatchId());
        assertEquals(36, batch.getBatchId().length());
        assertEquals(BatchStatus.PENDING, batch.getStatus());
        assertNotNull(batch.getCreatedAt());
        assertEquals(batch.getCreatedAt(), batch.getUpdatedAt());
    }

    @Test
    void onCreateKeepsProvidedIdAndStatus() {
        Batch batch = new Batch();
        batch.setBatchId("custom-id");
        batch.setStatus(BatchStatus.PROCESSING);

        batch.onCreate();

        assertEquals("custom-id", batch.getBatchId());
        assertEquals(BatchStatus.PROCESSING, batch.getStatus());
    }

    @Test
    void onUpdateRefreshesUpdatedAt() {
        Batch batch = new Batch();
        batch.onCreate();

        batch.onUpdate();

        assertTrue(!batch.getUpdatedAt().isBefore(batch.getCreatedAt()));
    }
}
