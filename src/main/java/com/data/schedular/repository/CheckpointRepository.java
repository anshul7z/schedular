package com.data.schedular.repository;

import com.data.schedular.domain.Checkpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    @Modifying
    @Query("delete from Checkpoint c where c.jobId = :jobId")
    int deleteByJobId(Long jobId);
}
