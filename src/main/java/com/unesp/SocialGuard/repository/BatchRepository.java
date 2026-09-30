package com.unesp.SocialGuard.repository;

import com.unesp.SocialGuard.domain.Batch;
import com.unesp.SocialGuard.domain.BatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BatchRepository extends JpaRepository<Batch, String> {

    List<Batch> findByCompanyId(String companyId);

    List<Batch> findByStatus(BatchStatus status);

    List<Batch> findByCompanyIdAndStatus(String companyId, BatchStatus status);
}
