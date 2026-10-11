package com.unesp.SocialGuard.repository;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BatchRepository extends JpaRepository<Batch, String> {

    List<Batch> findByCompanyId(String companyId);

    List<Batch> findByStatus(BatchStatus status);

    List<Batch> findByCompanyIdAndStatus(String companyId, BatchStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Batch b set b.status = :processing, b.updatedAt = :updatedAt "
            + "where b.batchId = :batchId and b.status = :pending")
    int claimIfPending(@Param("batchId") String batchId,
                       @Param("pending") BatchStatus pending,
                       @Param("processing") BatchStatus processing,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
