package com.amsidel.backend.repository;

import com.amsidel.backend.entity.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    List<LeaveType> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByCodeIgnoreCase(String code);
}
