package com.amsidel.backend.entity;

/** Who a menu access row is for. */
public enum AccessSubject {
    /** A user type: OWNER, ADMIN, MANAGER, EMPLOYEE (key = the role name). */
    ROLE,
    /** A designation (key = its id). */
    DESIGNATION,
    /** One person (key = the employee's id): when they have rows, only those count. */
    EMPLOYEE
}
