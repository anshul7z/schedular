package com.data.schedular.repository;

import com.data.schedular.domain.MigrationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {

    boolean existsByName(String name);

    @Query("select count(j) > 0 from MigrationJob j "
            + "where j.sourceConnection.id = :connectionId or j.targetConnection.id = :connectionId")
    boolean existsByConnectionId(Long connectionId);
}
