package com.amsidel.backend.entity;

/** Where a pay component sits on the payslip. Net pay = earnings - deductions + reimbursements. */
public enum PayComponentKind {
    EARNING,
    DEDUCTION,
    REIMBURSEMENT
}
