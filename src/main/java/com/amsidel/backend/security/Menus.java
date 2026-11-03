/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
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
    /**
     * Not a page: on Check in, no camera at all, only the time and GPS location (office, home and field visits).
     * Its own tick, and it counts only when given itself (not through "*"), so owners and admins keep the photo.
     */
    public static final String ATT_NO_PHOTO = "ATT_NO_PHOTO";
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
    public static final String NEWS = "NEWS";
    public static final String PERF_MY = "PERF_MY";
    public static final String PERF_TEAM = "PERF_TEAM";
    public static final String PERF_CYCLES = "PERF_CYCLES";
    public static final String PAYROLL_SALARIES = "PAYROLL_SALARIES";
    public static final String PAY_COMPONENTS = "PAY_COMPONENTS";
    public static final String PAYSLIPS = "PAYSLIPS";
    /** Not a page: typing a salary's income tax by hand (the Manual tax regime). */
    public static final String PAY_TAX_MANUAL = "PAY_TAX_MANUAL";
    /** Not a page: choosing a salary's tax regime (new, old or manual); without it a salary keeps its regime (Manual at first). */
    public static final String PAY_TAX_REGIME = "PAY_TAX_REGIME";
    public static final String MY_PAY = "MY_PAY";
    /** Team pay: the pay of the people below me (everyone in scope for owners and admins), read-only. */
    public static final String TEAM_PAY = "TEAM_PAY";
    // the product portal (paths under /portal/products)
    public static final String PROD_HOME = "PROD_HOME";
    public static final String PROD_ITEMS = "PROD_ITEMS";
    public static final String PROD_TYPES = "PROD_TYPES";
    public static final String PROD_SUPPLIERS = "PROD_SUPPLIERS";
    public static final String PROD_PURCHASES = "PROD_PURCHASES";
    public static final String PROD_QUOTES = "PROD_QUOTES";
    public static final String PROD_ORDERS = "PROD_ORDERS";
    public static final String PROD_INVOICES = "PROD_INVOICES";
    public static final String PROD_REPORTS = "PROD_REPORTS";
    public static final String PROD_PROJECTS = "PROD_PROJECTS";
    /** Project status updates for customers, with photos, and the customer's status link. */
    public static final String PROD_PROJECT_STATUS = "PROD_PROJECT_STATUS";
    public static final String PROD_LEDGER = "PROD_LEDGER";
    public static final String PROD_RECONCILE = "PROD_RECONCILE";
    public static final String PROD_MAIL = "PROD_MAIL";
    public static final String PROD_PAY = "PROD_PAY";
    /** Not a page: the team chat window in the portal. */
    public static final String CHAT = "CHAT";
    /** Not a page: starting group chats in Team chat. */
    public static final String CHAT_GROUP_CREATE = "CHAT_GROUP_CREATE";
    /** Not a page: adding people to a group chat I am in. */
    public static final String CHAT_GROUP_ADD = "CHAT_GROUP_ADD";
    /** Not a page: the Manual UPI (office) option on Pay bill. */
    public static final String PROD_PAY_MANUAL = "PROD_PAY_MANUAL";
    /** Checking and approving stock received by scanning item QR labels: opens the Scan stock in page. */
    public static final String PROD_STOCK_APPROVE = "PROD_STOCK_APPROVE";
    // the customer portal (paths under /portal/customers)
    /** Customer details: view, add, change and delete customers, with their quotations, invoices and what they owe. */
    public static final String CUSTOMERS = "CUSTOMERS";
    /** Not a page: the New quotation button on a customer's page (the quotation itself still needs Quotations). */
    public static final String CUST_NEW_QUOTE = "CUST_NEW_QUOTE";
    /** Not a page: the New invoice button on a customer's page (the invoice itself still needs Invoices). */
    public static final String CUST_NEW_INVOICE = "CUST_NEW_INVOICE";
    /** Not a page: adding customers in the customer portal (quotations, invoices and projects add theirs too). */
    public static final String CUST_ADD = "CUST_ADD";
    /** Not a page: changing a customer's details. */
    public static final String CUST_EDIT = "CUST_EDIT";
    /** Not a page: deleting a customer (only one with nothing recorded against them). */
    public static final String CUST_DELETE = "CUST_DELETE";
    /** Not a page: the types of evaluation a manager may choose when setting a goal (Team performance). */
    public static final String PERF_TYPE_GENERAL = "PERF_TYPE_GENERAL";
    public static final String PERF_TYPE_SALES = "PERF_TYPE_SALES";
    public static final String PERF_TYPE_ORDERS = "PERF_TYPE_ORDERS";
    public static final String PERF_TYPE_WORKLOG = "PERF_TYPE_WORKLOG";
    /** Not a page: sending staff mail by email (Both needs this and WhatsApp). */
    public static final String MAIL_EMAIL = "MAIL_EMAIL";
    /** Not a page: sending staff mail on WhatsApp. */
    public static final String MAIL_WHATSAPP = "MAIL_WHATSAPP";
    /** Not a page: staff mail to all employees (of a brand, or everyone). */
    public static final String MAIL_ALL = "MAIL_ALL";
    /** Not a page: staff mail to chosen people, at most the limit set on the Staff mail page per send. */
    public static final String MAIL_PICK = "MAIL_PICK";
    /** Not a page: pictures inside a staff mail (Add picture). */
    public static final String MAIL_PICTURE = "MAIL_PICTURE";
    /** Not a page: attachments (pictures, PDF, Word) on a staff mail (Attach files). */
    public static final String MAIL_ATTACH = "MAIL_ATTACH";
    /** Sub-menus that a tick on their parent menu does not give: each needs its own tick. */
    public static final java.util.Set<String> OWN_TICK = java.util.Set.of(PROD_STOCK_APPROVE, ATT_NO_PHOTO, CUST_NEW_QUOTE, CUST_NEW_INVOICE,
            CUST_ADD, CUST_EDIT, CUST_DELETE, MAIL_EMAIL, MAIL_WHATSAPP, MAIL_ALL, MAIL_PICK, MAIL_PICTURE, MAIL_ATTACH,
            PERF_TYPE_GENERAL, PERF_TYPE_SALES, PERF_TYPE_ORDERS, PERF_TYPE_WORKLOG, PAY_TAX_REGIME,
            CHAT_GROUP_CREATE, CHAT_GROUP_ADD);
    /** Any page of the product portal. */
    public static final String[] PRODUCT_PORTAL = {PROD_HOME, PROD_ITEMS, PROD_TYPES, PROD_SUPPLIERS, PROD_PURCHASES,
            PROD_QUOTES, PROD_ORDERS, PROD_INVOICES, PROD_REPORTS, PROD_PROJECTS, PROD_LEDGER, PROD_RECONCILE, PROD_MAIL, PROD_PAY, PROD_PAY_MANUAL, PROD_PROJECT_STATUS, PROD_STOCK_APPROVE};

    /** One menu; parent null for a top-level menu, path null for a menu that only holds sub-menus. */
    public record Def(String code, String name, String description, int checkpoint, String parent, String path, String icon) {
    }

    public static final List<Def> ALL = List.of(
            new Def("ATTENDANCE", "Attendance", "Check-in / check-out and field visits", 1000, null, null, "pi-map-marker"),
            new Def(ATT_CHECKIN, "Check in", "Own check-in, check-out and field visits", 1001, "ATTENDANCE", "/portal/attendance", "pi-camera"),
            new Def(ATT_TEAM, "Team attendance", "Attendance, photos and locations of the people below you", 1002, "ATTENDANCE", "/portal/attendance/team", "pi-users"),
            // under Attendance but its own tick (OWN_TICK), and never through "*": owners and admins keep the photo
            new Def(ATT_NO_PHOTO, "Check in without photo", "On Check in: no camera, only the time and GPS location are recorded (office, home and field visits); given on top of Check in (its own tick). Without it, check-in works with the live photo as before", 1003, "ATTENDANCE", null, "pi-map-marker"),

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
            // how and to whom staff mail may go, each its own tick (giving Staff mail does not give them)
            new Def(MAIL_EMAIL, "Send by email", "Staff mail: send by office email (Both needs this and WhatsApp); its own tick", 5001, STAFF_MAIL, null, "pi-envelope"),
            new Def(MAIL_WHATSAPP, "Send by WhatsApp", "Staff mail: send on WhatsApp (Both needs this and email); its own tick", 5002, STAFF_MAIL, null, "pi-whatsapp"),
            new Def(MAIL_ALL, "All employees", "Staff mail: send to all employees (of a brand, or everyone); its own tick", 5003, STAFF_MAIL, null, "pi-users"),
            new Def(MAIL_PICK, "Choose people", "Staff mail: send to chosen people, at most the number an admin sets on the Staff mail page (5 at first) in one send; its own tick", 5004, STAFF_MAIL, null, "pi-user-plus"),
            new Def(MAIL_PICTURE, "Add picture", "Staff mail: pictures inside the message (Add picture), sent by email; its own tick", 5005, STAFF_MAIL, null, "pi-image"),
            new Def(MAIL_ATTACH, "Attach files", "Staff mail: attach pictures, PDF and Word files (Attach files), sent by email; its own tick", 5006, STAFF_MAIL, null, "pi-paperclip"),

            new Def("PERFORMANCE", "Performance", "Yearly performance: skills, goals, ratings and achievements", 6000, null, null, "pi-chart-line"),
            new Def(PERF_MY, "My performance", "Own skills, intro, goals, self-rating and achievements", 6001, "PERFORMANCE", "/portal/performance", "pi-star"),
            new Def(PERF_TEAM, "Team performance", "Set goals, rate and give achievements to the people below you", 6002, "PERFORMANCE", "/portal/performance/team", "pi-users"),
            new Def(PERF_CYCLES, "Review cycles", "Open and close the yearly review periods", 6003, "PERFORMANCE", "/portal/performance/cycles", "pi-calendar-clock"),
            // the types of evaluation a manager may choose for a new goal (Team performance), each its own tick
            new Def(PERF_TYPE_GENERAL, "Goal type: General", "Team performance: set General goals (progress moved by hand, rated in stars); its own tick", 6004, "PERFORMANCE", null, "pi-flag"),
            new Def(PERF_TYPE_SALES, "Goal type: Sales & marketing", "Team performance: set Sales & marketing goals (a target amount; the person records what they achieved); its own tick", 6005, "PERFORMANCE", null, "pi-chart-line"),
            new Def(PERF_TYPE_ORDERS, "Goal type: Sales orders", "Team performance: set Sales orders goals (a target reached by confirmed sales orders); its own tick", 6006, "PERFORMANCE", null, "pi-check-square"),
            new Def(PERF_TYPE_WORKLOG, "Goal type: Task worklog", "Team performance: set the Task worklog goal (the person's work log tasks as sub-tasks, with their logged hours, completion, self rating and review); its own tick", 6007, "PERFORMANCE", null, "pi-clock"),

            new Def("PAYROLL", "Payroll", "Salaries, monthly payslips and your own pay", 7000, null, null, "pi-wallet"),
            new Def(PAY_TAX_MANUAL, "Manual income tax", "On Salaries: choose the Manual tax regime and type a salary's income tax by hand (the new and old regimes work it out); its own tick", 7090, null, null, "pi-calculator"),
            new Def(PAY_TAX_REGIME, "Tax regime selection", "On Salaries: choose a salary's tax regime (new, old or manual). Without it a salary keeps its regime, and a new salary is Manual; its own tick", 7091, null, null, "pi-sliders-h"),
            new Def(PAYROLL_SALARIES, "Salaries", "Each employee's salary for the financial year: monthly and yearly", 7001, "PAYROLL", "/portal/payroll", "pi-indian-rupee"),
            new Def(PAY_COMPONENTS, "Pay components", "Earnings, deductions and reimbursements used in salaries", 7002, "PAYROLL", "/portal/payroll/components", "pi-list"),
            new Def(PAYSLIPS, "Monthly payslips", "Make, check and publish each month's payslips", 7003, "PAYROLL", "/portal/payroll/payslips", "pi-file"),
            // checkpoint 8000 kept from when it was a menu of its own, so existing access stays as it is
            new Def(MY_PAY, "My pay", "Own salary, payslips (PDF) and pay for the year", 8000, "PAYROLL", "/portal/my-pay", "pi-money-bill"),
            new Def(TEAM_PAY, "Team pay", "The pay of the people reporting to you, as they see it in My pay: salary, published payslips (PDF) and pay for the year; owners and admins see everyone in their scope, by brand and person. Read-only", 7004, "PAYROLL", "/portal/payroll/team-pay", "pi-users"),

            new Def("ADMIN", "Administration", "Portal set-up", 9000, null, null, "pi-shield"),
            new Def(NEWS, "News & announcements", "Add, change and remove the news on the dashboard (and the offers on the website): discounts, promotions, birthdays, important notices", 9003, "ADMIN", "/portal/admin/news", "pi-megaphone"),
            new Def(BRANDS, "Brands", "The group's brands: details, logo, owners and GST number; changing them needs the Owner user type", 9002, "ADMIN", "/portal/admin/brands", "pi-building"),
            new Def(MENU_ACCESS, "Menu access", "Which user types and designations see which menus; changing it needs the Admin user type", 9001, "ADMIN", "/portal/admin/menu-access", "pi-lock"),

            // ---- product portal ----
            new Def(PROD_HOME, "Overview", "Product portal: sales, purchases, stock and money owed at a glance", 11000, null, "/portal/products", "pi-chart-bar"),
            new Def("PROD_CATALOG", "Products & stock", "Product types, items, prices and stock", 11100, null, null, "pi-box"),
            new Def(PROD_ITEMS, "Items & stock", "Items with their prices; stock in, out and on hand", 11101, "PROD_CATALOG", "/portal/products/items", "pi-box"),
            new Def(PROD_TYPES, "Product types", "The types of product each brand sells", 11102, "PROD_CATALOG", "/portal/products/types", "pi-tags"),
            // under Products & stock but its own tick (OWN_TICK: giving Products & stock does not give it); it opens the Scan stock in page
            new Def(PROD_STOCK_APPROVE, "Approve scanned stock", "Check and approve stock received by scanning item QR labels (on the Scan stock in page); approving adds it to stock and makes new items from their labels (its own tick)", 11103, "PROD_CATALOG", "/portal/products/stock-scan", "pi-check-square"),
            new Def("PROD_BUYING", "Purchasing", "Suppliers, purchase bills and supplier payments", 11200, null, null, "pi-truck"),
            new Def(PROD_SUPPLIERS, "Suppliers", "Supplier details, bank details and what is owed to them", 11201, "PROD_BUYING", "/portal/products/suppliers", "pi-building"),
            new Def(PROD_PURCHASES, "Purchase bills", "Goods bought: bills, stock received and payments to suppliers", 11202, "PROD_BUYING", "/portal/products/purchases", "pi-download"),
            new Def("PROD_SELLING", "Sales", "Quotations, sales orders, invoices and customer payments", 11300, null, null, "pi-shopping-cart"),
            new Def(PROD_QUOTES, "Quotations", "Quotations to customers (PDF), made into invoices", 11302, "PROD_SELLING", "/portal/products/quotations", "pi-file-edit"),
            new Def(PROD_ORDERS, "Sales orders", "The customer's confirmed order (from a quotation, items removed or changed), before the invoice; confirming it sets the project to Confirmed", 11304, "PROD_SELLING", "/portal/products/sales-orders", "pi-check-square"),
            new Def(PROD_INVOICES, "Invoices", "Final bills (PDF), stock out and customer payments", 11303, "PROD_SELLING", "/portal/products/invoices", "pi-receipt"),
            new Def("PROD_PROJECTS_GRP", "Projects", "Customer projects, and their status updates for the customer", 11550, null, null, "pi-briefcase"),
            new Def(PROD_PROJECTS, "Projects", "Customer projects: team, tasks, quotations, invoices and payments in one place", 11500, "PROD_PROJECTS_GRP", "/portal/products/projects", "pi-briefcase"),
            new Def(PROD_PROJECT_STATUS, "Project status", "Post status updates with photos for a project's customer, and share the customer's status link (no sign-in)", 11501, "PROD_PROJECTS_GRP", "/portal/products/project-status", "pi-images"),
            new Def("PROD_ACCOUNTS", "Accounts", "Other income and expenses, bank statements and the P&L reconciler", 11600, null, null, "pi-wallet"),
            new Def(PROD_LEDGER, "Income & expenses", "Investment, loans, capital and other income; transport, fixing, discounts and other expenses, by bill and project", 11601, "PROD_ACCOUNTS", "/portal/products/ledger", "pi-arrow-right-arrow-left"),
            new Def(PROD_RECONCILE, "P&L reconciler", "Profit and loss by week, month or year; bank statements tallied with the portal; invoices paid short", 11602, "PROD_ACCOUNTS", "/portal/products/reconciler", "pi-calculator"),
            new Def(PROD_MAIL, "Customer & supplier mail", "Personal mail and WhatsApp messages to customers and suppliers, like staff mail; the office is copied on every mail", 11700, null, "/portal/products/mail", "pi-envelope"),
            new Def(PROD_PAY, "Pay bill", "Customers pay their bills online by card or UPI through the brand's Razorpay account; payment history and refunds", 11800, null, "/portal/products/pay", "pi-credit-card"),
            new Def(CHAT, "Team chat", "The chat window in the portal: one-to-one and group chats with colleagues, admins and owners who have this too; messages are cleared after the set number of days", 12000, null, null, "pi-comments"),
            // group chats, each its own tick (Team chat alone: one-to-one chats, and the groups others add you to)
            new Def(CHAT_GROUP_CREATE, "Start group chats", "Team chat: start a group chat with a name and its people; its own tick", 12001, CHAT, null, "pi-users"),
            new Def(CHAT_GROUP_ADD, "Add people to groups", "Team chat: add people to a group chat you are in; its own tick", 12002, CHAT, null, "pi-user-plus"),
            new Def(PROD_PAY_MANUAL, "Manual UPI (office)", "On Pay bill: a customer pays at the office to the brand's UPI QR code, and the staff record the UPI reference; given on top of Pay bill (its own tick: giving Pay bill does not give it)", 11801, null, null, "pi-qrcode"),
            new Def(PROD_REPORTS, "Reports", "Sales, purchases, stock and payments by customer, bill, supplier, product and item; supplier spend charts", 11400, null, "/portal/products/reports", "pi-chart-line"),

            // ---- customer portal ----
            new Def(CUSTOMERS, "Customers", "Customer details: view, add, change and delete customers, with their quotations, invoices and what they owe", 13000, null, "/portal/customers", "pi-users"),
            // what may be done in the customer portal and buttons on a customer's page, each its own tick (giving Customers
            // does not give them; Customers alone is view only)
            new Def(CUST_ADD, "Add customer", "Add customers in the customer portal (the New customer button on quotations, invoices and projects works with those menus); its own tick", 13003, CUSTOMERS, null, "pi-user-plus"),
            new Def(CUST_EDIT, "Change customer", "Change a customer's details, bank and UPI, and make them active or inactive; its own tick", 13004, CUSTOMERS, null, "pi-pencil"),
            new Def(CUST_DELETE, "Delete customer", "Delete a customer who has no quotations, invoices, projects, payments or tasks; its own tick", 13005, CUSTOMERS, null, "pi-trash"),
            new Def(CUST_NEW_QUOTE, "New quotation button", "On a customer's page: the New quotation button, which opens a new quotation for the customer in the product portal (also needs Quotations); its own tick", 13001, CUSTOMERS, null, "pi-file-edit"),
            new Def(CUST_NEW_INVOICE, "New invoice button", "On a customer's page: the New invoice button, which opens a new invoice for the customer in the product portal (also needs Invoices); its own tick", 13002, CUSTOMERS, null, "pi-receipt")
    );

    /**
     * A menu that takes over from older checkpoints (moved out of another portal): when it first appears, whoever
     * had any of them (a person, a user type or a designation) gets it, so nobody loses a page. Customers was under
     * Product portal > Sales (11300) as 11301. The New quotation / New invoice buttons used to show for everyone with
     * Quotations / Invoices (or the whole Sales menu), so those people keep them. Adding, changing and deleting
     * customers came with Customers (13000, before that 11301 / 11300), so those people keep them too. The staff mail
     * ticks came with Staff mail (5000), the goal types with Team performance (6002) or the whole Performance menu, and
     * choosing the tax regime with Salaries (7001) or the whole Payroll menu, and the group chat ticks with Team chat (12000).
     */
    public static final java.util.Map<String, List<String>> TAKES_OVER = java.util.Map.ofEntries(
            java.util.Map.entry(CUSTOMERS, List.of("11301", "11300")),
            java.util.Map.entry(CUST_NEW_QUOTE, List.of("11302", "11300")),
            java.util.Map.entry(CUST_NEW_INVOICE, List.of("11303", "11300")),
            java.util.Map.entry(CUST_ADD, List.of("13000", "11301", "11300")),
            java.util.Map.entry(CUST_EDIT, List.of("13000", "11301", "11300")),
            java.util.Map.entry(CUST_DELETE, List.of("13000", "11301", "11300")),
            java.util.Map.entry(MAIL_EMAIL, List.of("5000")),
            java.util.Map.entry(MAIL_WHATSAPP, List.of("5000")),
            java.util.Map.entry(MAIL_ALL, List.of("5000")),
            java.util.Map.entry(MAIL_PICK, List.of("5000")),
            java.util.Map.entry(MAIL_PICTURE, List.of("5000")),
            java.util.Map.entry(MAIL_ATTACH, List.of("5000")),
            java.util.Map.entry(PERF_TYPE_GENERAL, List.of("6002", "6000")),
            java.util.Map.entry(PERF_TYPE_SALES, List.of("6002", "6000")),
            java.util.Map.entry(PERF_TYPE_ORDERS, List.of("6002", "6000")),
            java.util.Map.entry(PERF_TYPE_WORKLOG, List.of("6002", "6000")),
            java.util.Map.entry(PAY_TAX_REGIME, List.of("7001", "7000")),
            java.util.Map.entry(CHAT_GROUP_CREATE, List.of("12000")),
            java.util.Map.entry(CHAT_GROUP_ADD, List.of("12000")));

    /** Given to anyone with people reporting to them, whatever their user type or menu access. */
    public static final List<String> FOR_TEAM_LEADS = List.of(PERF_TEAM, PERF_TYPE_GENERAL);

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
            PAY_TAX_REGIME, List.of("8.2"),
            PAYSLIPS, List.of("8.2"));

    private Menus() {
    }
}
