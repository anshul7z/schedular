package com.data.schedular.repository;

import com.data.schedular.domain.JobRun;
import com.data.schedular.domain.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Page<JobRun> findByJobIdOrderByStartedAtDescIdDesc(Long jobId, Pageable pageable);

    Optional<JobRun> findFirstByJobIdOrderByStartedAtDescIdDesc(Long jobId);

    boolean existsByJobIdAndStatus(Long jobId, RunStatus status);

    /** Runs still marked RUNNING that this instance started (or that predate node tracking). */
    @Query("select r from JobRun r where r.status = com.data.schedular.domain.RunStatus.RUNNING "
            + "and (r.nodeId = :nodeId or r.nodeId is null)")
    List<JobRun> findRunningOnNode(String nodeId);
}
