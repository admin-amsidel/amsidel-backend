package com.amsidel.backend.repository;

import com.amsidel.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Projects with their brand and customer; brandId null = every brand. */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("select p from Project p join fetch p.brand b join fetch p.customer left join fetch p.owner"
            + " where (:brandId is null or b.id = :brandId) order by p.createdAt desc, p.id desc")
    List<Project> forBrand(@Param("brandId") Long brandId);

    @Query("select p from Project p join fetch p.brand join fetch p.customer left join fetch p.owner left join fetch p.createdBy"
            + " left join fetch p.updatedBy where p.id = :id")
    Optional<Project> findFull(@Param("id") Long id);
}
