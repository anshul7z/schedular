package com.data.schedular.repository;

import com.data.schedular.domain.CollectionMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionMappingRepository extends JpaRepository<CollectionMapping, Long> {

    List<CollectionMapping> findByJobIdOrderByOrderIndex(Long jobId);
}
