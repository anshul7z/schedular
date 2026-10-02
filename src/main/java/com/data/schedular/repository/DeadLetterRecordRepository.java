package com.data.schedular.repository;

import com.data.schedular.domain.DeadLetterRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRecordRepository extends JpaRepository<DeadLetterRecord, Long> {

    Page<DeadLetterRecord> findByRunIdOrderById(Long runId, Pageable pageable);
}
