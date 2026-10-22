package com.amsidel.backend.security;

import com.amsidel.backend.entity.Employee;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Clears {@link SignedInCache} whenever an employee row is changed or removed: once when the change is written and
 * again after it is committed, so a request running in between cannot keep the old values. (A new reporting line
 * also changes whether the manager leads a team: EmployeeService clears everyone's entry for that.)
 */
@Component
@RequiredArgsConstructor
public class EmployeeCacheListener {

    private final SignedInCache cache;

    @PostUpdate
    @PostRemove
    void changed(Employee employee) {
        Long id = employee.getId();
        cache.evict(id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.evict(id);
                }
            });
        }
    }
}
