package com.data.schedular.repository;

import com.data.schedular.domain.ConnectionDef;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionDefRepository extends JpaRepository<ConnectionDef, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
