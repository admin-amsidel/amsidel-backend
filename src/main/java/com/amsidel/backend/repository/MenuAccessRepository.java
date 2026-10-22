package com.amsidel.backend.repository;

import com.amsidel.backend.entity.AccessSubject;
import com.amsidel.backend.entity.MenuAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuAccessRepository extends JpaRepository<MenuAccess, Long> {

    @Modifying
    @Query("delete from MenuAccess a where a.subjectType = :type and a.subjectKey = :key")
    void deleteSubject(@Param("type") AccessSubject type, @Param("key") String key);
}
