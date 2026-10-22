package com.amsidel.backend.entity;

/** How a leave type's balance is built. */
public enum LeaveAllocation {
    /** Everyone gets annualQuota days each calendar year (casual, sick, earned, restricted holiday). */
    ANNUAL,
    /** No yearly quota; an admin or manager grants days to a person (comp-off, paternity). */
    GRANTED,
    /** No balance; only counted (leave without pay). */
    UNLIMITED
}
