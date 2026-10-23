package com.amsidel.backend.security;

import java.util.List;

/**
 * The portal's menus and sub-menus with their checkpoint numbers, kept in the menus table at start-up
 * (MenuBootstrap). A new page gets a line here, an Angular route, and a @Checkpoint on its API.
 * Profile and Change PIN are always available and are not listed.
 */
public final class Menus {

    public static final String ATT_CHECKIN = "ATT_CHECKIN";
    public static final String ATT_TEAM = "ATT_TEAM";
    public static final String LEAVE_MY = "LEAVE_MY";
    public static final String LEAVE_APPROVALS = "LEAVE_APPROVALS";
    public static final String LEAVE_SETTINGS = "LEAVE_SETTINGS";
    public static final String WORKLOG_MY = "WORKLOG_MY";
    public static final String TASKS = "TASKS";
    public static final String WORK_RULES = "WORK_RULES";
    public static final String EMP_LIST = "EMP_LIST";
    public static final String ORG_CHART = "ORG_CHART";
    public static final String DESIGNATIONS = "DESIGNATIONS";
    public static final String STAFF_MAIL = "STAFF_MAIL";
    public static final String MENU_ACCESS = "MENU_ACCESS";
    public static final String BRANDS = "BRANDS";
    public static final String PERF_MY = "PERF_MY";
    public static final String PERF_TEAM = "PERF_TEAM";
    public static final String PERF_CYCLES = "PERF_CYCLES";
    public static final String PAYROLL_SALARIES = "PAYROLL_SALARIES";
    public static final String PAY_COMPONENTS = "PAY_COMPONENTS";
    public static final String PAYSLIPS = "PAYSLIPS";
    public static final String MY_PAY = "MY_PAY";

    /** One menu; parent null for a top-level menu, path null for a menu that only holds sub-menus. */
    public record Def(String code, String name, String description, int checkpoint, String parent, String path, String icon) {
    }

    public static final List<Def> ALL = List.of(
            new Def("ATTENDANCE", "Attendance", "Check-in / check-out and field visits", 1000, null, null, "pi-map-marker"),
            new Def(ATT_CHECKIN, "Check in", "Own check-in, check-out and field visits", 1001, "ATTENDANCE", "/portal/attendance", "pi-camera"),
            new Def(ATT_TEAM, "Team attendance", "Attendance, photos and locations of the people below you", 1002, "ATTENDANCE", "/portal/attendance/team", "pi-users"),

            new Def("LEAVE", "Leave", "Leave requests, approvals and settings", 2000, null, null, "pi-calendar"),
            new Def(LEAVE_MY, "My leave", "Own balances and requests", 2001, "LEAVE", "/portal/leave", "pi-calendar"),
            new Def(LEAVE_APPROVALS, "Approvals", "Approve leave and grant comp-off for your team", 2002, "LEAVE", "/portal/leave/approvals", "pi-check-square"),
            new Def(LEAVE_SETTINGS, "Leave settings", "Leave types, yearly limits and restricted holidays", 2003, "LEAVE", "/portal/leave/settings", "pi-cog"),

            new Def("WORKLOG", "Work log", "Daily work log, tasks and minimum hours", 3000, null, null, "pi-clock"),
            new Def(WORKLOG_MY, "My work log", "Own work log; managers also see their team's", 3001, "WORKLOG", "/portal/worklog", "pi-clock"),
            new Def(TASKS, "Tasks", "Create tasks and assign them to employees", 3002, "WORKLOG", "/portal/worklog/tasks", "pi-list-check"),
            new Def(WORK_RULES, "Work hour rules", "Minimum hours per designation level", 3003, "WORKLOG", "/portal/worklog/rules", "pi-sliders-h"),

            new Def("EMPLOYEES", "Employees", "Employee records and the organisation", 4000, null, null, "pi-users"),
            new Def(EMP_LIST, "All employees", "Employee list; adding and changing records needs the Admin user type", 4001, "EMPLOYEES", "/portal/employees", "pi-id-card"),
            new Def(ORG_CHART, "Org chart", "The whole reporting structure", 4002, "EMPLOYEES", "/portal/org-chart", "pi-sitemap"),
            new Def(DESIGNATIONS, "Designations", "Designations and levels", 4003, "EMPLOYEES", "/portal/designations", "pi-list"),

            new Def(STAFF_MAIL, "Staff mail", "Personal mail and WhatsApp messages to employees", 5000, null, "/portal/mail", "pi-envelope"),

            new Def("PERFORMANCE", "Performance", "Yearly performance: skills, goals, ratings and achievements", 6000, null, null, "pi-chart-line"),
            new Def(PERF_MY, "My performance", "Own skills, intro, goals, self-rating and achievements", 6001, "PERFORMANCE", "/portal/performance", "pi-star"),
            new Def(PERF_TEAM, "Team performance", "Set goals, rate and give achievements to the people below you", 6002, "PERFORMANCE", "/portal/performance/team", "pi-users"),
            new Def(PERF_CYCLES, "Review cycles", "Open and close the yearly review periods", 6003, "PERFORMANCE", "/portal/performance/cycles", "pi-calendar-clock"),

            new Def("PAYROLL", "Payroll", "Salaries, monthly payslips and your own pay", 7000, null, null, "pi-wallet"),
            new Def(PAYROLL_SALARIES, "Salaries", "Each employee's salary for the financial year: monthly and yearly", 7001, "PAYROLL", "/portal/payroll", "pi-indian-rupee"),
            new Def(PAY_COMPONENTS, "Pay components", "Earnings, deductions and reimbursements used in salaries", 7002, "PAYROLL", "/portal/payroll/components", "pi-list"),
            new Def(PAYSLIPS, "Monthly payslips", "Make, check and publish each month's payslips", 7003, "PAYROLL", "/portal/payroll/payslips", "pi-file"),
            // checkpoint 8000 kept from when it was a menu of its own, so existing access stays as it is
            new Def(MY_PAY, "My pay", "Own salary, payslips (PDF) and pay for the year", 8000, "PAYROLL", "/portal/my-pay", "pi-money-bill"),

            new Def("ADMIN", "Administration", "Portal set-up", 9000, null, null, "pi-shield"),
            new Def(BRANDS, "Brands", "The group's brands: details, logo, owners and GST number; changing them needs the Owner user type", 9002, "ADMIN", "/portal/admin/brands", "pi-building"),
            new Def(MENU_ACCESS, "Menu access", "Which user types and designations see which menus; changing it needs the Admin user type", 9001, "ADMIN", "/portal/admin/menu-access", "pi-lock")
    );

    /** Given to anyone with people reporting to them, whatever their user type or menu access. */
    public static final List<String> FOR_TEAM_LEADS = List.of(PERF_TEAM);

    /**
     * User types that get a menu when it first appears in a database that already has menu access set up
     * (a new install gets the full defaults in MenuBootstrap instead). Admins and owners already have "*".
     */
    public static final java.util.Map<String, List<String>> NEW_MENU_ROLES = java.util.Map.of(
            PERF_MY, List.of("EMPLOYEE", "MANAGER"),
            PERF_TEAM, List.of("MANAGER"),
            MY_PAY, List.of("EMPLOYEE", "MANAGER"));

    /**
     * Designations (by level code) that get a menu when it first appears, e.g. HR Head / Manager for payroll.
     * Only used if that designation exists; more are given under Administration > Menu access.
     */
    public static final java.util.Map<String, List<String>> NEW_MENU_DESIGNATIONS = java.util.Map.of(
            "PAYROLL", List.of("8.2"),
            PAYROLL_SALARIES, List.of("8.2"),
            PAY_COMPONENTS, List.of("8.2"),
            PAYSLIPS, List.of("8.2"));

    private Menus() {
    }
}
