package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Designation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DesignationRepository extends JpaRepository<Designation, Long> {

    /** Most junior first: level 1 Trainee ... level 10 CEO. */
    List<Designation> findAllByOrderByLevelNumberAscLevelCodeAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByLevelCode(String levelCode);
}
