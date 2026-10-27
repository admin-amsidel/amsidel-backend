package com.amsidel.backend.repository;

import com.amsidel.backend.entity.UserType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTypeRepository extends JpaRepository<UserType, Long> {

    Optional<UserType> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<UserType> findAllByOrderByBuiltInDescNameAsc();
}
