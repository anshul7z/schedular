package com.data.schedular.repository;

import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Page<JobRun> findByJobIdOrderByStartedAtDesc(Long jobId, Pageable pageable);

    boolean existsByJobIdAndStatus(Long jobId, RunStatus status);
}
