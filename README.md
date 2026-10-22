# amsidel-backend
Amsidel Java Backend: products for the public website, and the staff portal (employee login) for every brand in the Amsidel group.

Spring Boot 4.1 · Java 21 · PostgreSQL (Supabase) · Spring Security with JWT access tokens.

## Run locally

Needs JDK 21 (`JAVA_HOME` pointing at it). The Maven wrapper downloads Maven 3.9.

```sh
./mvnw spring-boot:run                     # against the Supabase database in application.properties
./mvnw test                                # tests use an in-memory H2 database, never Supabase
```

Careful: `spring-boot:run` always uses the Supabase database in application.properties, even with
`-Dspring-boot.run.profiles=test` (Spring Boot 4 ignores the old `useTestClasspath` option, so the test settings are not loaded).

## First start

On an empty database the app creates brand **DW – Amsidel Doors & Windows** and one owner account:
user ID `owner`, PIN `Ams@2026` (settings `app.bootstrap.*`). The owner must choose a new PIN at first sign-in,
then adds everyone else from the portal (`/login` on the website).

## Login and tokens

1. `POST /api/auth/login` with `{"userId": "...", "password": "<PIN>"}` returns `{token, tokenType: "asd", expiresAt, user}`.
2. Send `Authorization: asd <token>` with every other call (the standard `Bearer` scheme is not accepted). A token lasts 12 hours (`app.jwt.validity`).

- **PIN rule:** at least 2 letters, 1 special character and 3 numbers, no spaces (`PinPolicy`).
- **Lockout:** 5 wrong PINs lock the account for 15 minutes (`app.login.*`); an admin's PIN reset unlocks it.
- **Temporary PINs:** new accounts and admin resets must change their PIN first; until then only `/api/auth/**` works.
- **Instant sign-out:** a PIN change or reset, or disabling the account, makes older tokens stop working.

## Roles (user types)

| Role | Can do |
|---|---|
| OWNER | Everything, every brand; the only role that adds owners, admins and brands; always every menu |
| ADMIN | Manage managers and employees of their brand (every brand if the admin has no brand) |
| MANAGER, EMPLOYEE | Their own pages; managers also their team's |

## Menu access (menus and checkpoints)

Which menus (and the APIs behind them) each person may use is kept in the database:

- `menus`: code, name, description, **checkpoint number**, parent menu, page path, icon, order. Filled from
  `security/Menus.java` on every start (Attendance 1000: Check in 1001, Team attendance 1002; Leave 2000: My leave
  2001, Approvals 2002, Leave settings 2003; Work log 3000: My work log 3001, Tasks 3002, Work hour rules 3003;
  Employees 4000: All employees 4001, Org chart 4002, Designations 4003; Staff mail 5000; Administration 9000:
  Menu access 9001).
- `menu_access`: `subject_type` `EMPLOYEE` (key = employee id), `ROLE` (key = OWNER/ADMIN/MANAGER/EMPLOYEE) or
  `DESIGNATION` (key = designation id), and a `checkpoint` number or `*` (every menu). A menu's checkpoint also
  gives its sub-menus.

A person with `EMPLOYEE` rows of their own gets **only those**; anyone else gets their role's checkpoints **plus**
their designation's. Nothing at all: `/api/auth/access` answers 403 "No permission granted…" and the portal shows
that page. Owners always have `*`. The first start gives
ADMIN `*`, MANAGER 1001, 1002, 2001, 2002, 3001 and EMPLOYEE 1001, 2001, 3001. My profile and Change PIN are always
there. Each API carries `@Checkpoint(...)` and returns 403 without one of its menus (the services still limit whose
data is seen). Whatever the menu access says, **changing employee records and changing menu access need the OWNER or
ADMIN role**, so nobody can give themselves more rights. The tables are kept in memory; changes made through the
portal apply at once (another running server picks them up on restart).

| Method | Path | Who |
|---|---|---|
| GET | `/api/auth/access` (`all`, `codes`, `menus` tree) | signed in |
| GET | `/api/menu-access/menus`, `/api/menu-access` | Menu access menu |
| PUT | `/api/menu-access/{EMPLOYEE\|ROLE\|DESIGNATION}/{key}` `{checkpoints: ["1001", "3002"] or ["*"]}` (a person with `[]` goes back to role + designation) | Menu access menu + OWNER/ADMIN |

## API

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/login` | anyone |
| GET | `/api/auth/me` | signed in |
| POST | `/api/auth/change-password` `{currentPassword, newPassword}` | signed in |
| GET | `/api/employees?brandId=` | owner, admin |
| GET | `/api/employees/{id}` | owner, admin |
| POST | `/api/employees` (first PIN in `password`) | owner, admin |
| PUT | `/api/employees/{id}` | owner, admin |
| POST | `/api/employees/{id}/reset-password` `{newPassword}` | owner, admin |
| GET | `/api/brands` | signed in |
| POST, PUT | `/api/brands`, `/api/brands/{id}` | owner |
| GET | `/api/people/me/line` (chain from the top down to me + my direct reports) | signed in |
| GET | `/api/people/{id}/line` | that person's managers above them, owners, admins |
| GET | `/api/people/org-chart` | owner, admin |
| GET | `/api/people/{id}/photo` | signed in |
| GET | `/api/designations` | signed in |
| POST, PUT | `/api/designations`, `/api/designations/{id}` | owner |
| GET | `/api/products`, `/api/products/{id}` | anyone |
| POST, PUT, DELETE | `/api/products/**` | owner, admin |

### Designations and reporting lines

Every employee can have a designation (job title + level) and a "reports to" person. Levels created on first start:
1 Trainee, 2 Associate, 3 Team Member, 4 Team Lead, 5 Team Manager, 6.1 Branch Manager / Head,
6.2 Finance Manager, 7 Regional Head / Manager, 8 HR Head / Manager, 9 CIO, 10 CEO. `levelNumber` ranks seniority
(peers share it); `levelCode` is what people see. Designation is separate from `role`, which is portal access.
A reporting line that loops back to the same person is refused.

### Work log

Everyone logs the tasks they worked on per day (task ID, hours in quarters, description; several per day, in order).
Minimum hours: the person's own target (set by an owner, admin or anyone above them in the reporting line), else
their designation level's rule, else the default rule (level 0: 9h a day, 54h a week, "Half Day Leave" below 4.5h,
"Full Day Leave" below 1h). Logs are visible to the person, everyone above them, and owners/admins in scope.

| Method | Path | Who |
|---|---|---|
| GET | `/api/worklog?from=&to=&employeeId=` (target, entries, leave in the range) | the person and those who may see them |
| GET | `/api/worklog/team` | signed in |
| POST, PUT, DELETE | `/api/worklog/entries`, `/api/worklog/entries/{id}` | the person themselves |
| GET | `/api/worklog/rules` | signed in |
| PUT | `/api/worklog/rules` (whole list; level 0 required) | owner, admin |
| PUT | `/api/worklog/targets/{employeeId}` `{dailyMinHours, weeklyMinHours}` (null = level rule) | owner, admin, their managers |

### Tasks

A work log entry must name an existing, open task assigned to the person (else 400: "Task X does not exist",
"... is closed", "... is not assigned to you"). Editing an entry without changing its task keeps it even if the task
was closed since. Owners and group admins manage every task; a brand admin manages group-wide tasks and their
brand's, and assigns only people they manage.

| Method | Path | Who |
|---|---|---|
| GET | `/api/tasks/mine` (open tasks assigned to me) | signed in |
| GET | `/api/tasks?status=OPEN\|CLOSED` (with assignees and hours logged) | owner, admin |
| POST | `/api/tasks` `{code?, title, description?, status?, brandId?, assigneeIds}` (empty code = next `TSK-0001`) | owner, admin |
| PUT | `/api/tasks/{id}` (same body) | owner, admin |

### Attendance (check-in / check-out)

For people who work from home or at sites. Each **entry** has a check-in with a **live camera photo** (the portal has
no gallery/file upload), the work mode `OFFICE` / `WFH` / `FIELD`, and the GPS position, and a check-out.
`OFFICE` / `WFH`: one entry per day (Asia/Kolkata day), closed with "End work today". `FIELD`: one entry per visit,
with the `place` (required), closed with "Close visit" and an optional `note` (meeting status); then the next visit
can start. Only one entry is open at a time; after the day's first entry only field visits can be added.
The **server** sets every time. No location: still accepted, flagged `LOCATION_MISSING`; accuracy worse than
200 m (`app.attendance.low-accuracy-metres`): flagged `LOW_ACCURACY`. Device (platform, OS, app version) and the
client IP (first `X-Forwarded-For` entry behind a proxy) are stored. Check-out records the time and location (no
photo). Lists and reports return one **day** per person (first in, last out, minutes in closed entries, modes,
flags) with its `entries`.

Photos: JPEG/PNG only (checked by their first bytes), at most 2 MB, re-encoded as JPEG (which removes all EXIF,
including any GPS in the file) and saved at `{app.attendance.photo-dir}/attendance/{yyyy}/{MM}/{employeeId}/{dd}.jpg` (`{dd}-2.jpg`, `{dd}-3.jpg`...
for more field visits that day).
They are served only through the API. **The photo folder must be on a disk that survives restarts and deploys**:
set `ATTENDANCE_PHOTO_DIR` to a persistent disk (on Render: attach a disk and point this at its mount path).

People see their own days; those above them in the reporting line, and owners/admins in scope, see their team's
(same rule as the work log).

| Method | Path | Who |
|---|---|---|
| GET | `/api/attendance/today` (`date`, `zone`, my `day` with its entries, or null) | signed in |
| POST | `/api/attendance/check-in` multipart: `photo` + `workMode`, `place` (field), `latitude?`, `longitude?`, `accuracy?`, `platform?`, `osVersion?`, `appVersion?` | signed in (409 while an entry is open, or a second office/home entry) |
| POST | `/api/attendance/check-out` `{latitude?, longitude?, accuracy?, note?}` closes the open entry | signed in (400 before check-in, 409 if nothing is open) |
| GET | `/api/attendance/me?from=&to=` (default last 30 days, at most 62) | signed in |
| GET | `/api/attendance/team` | signed in (people I may see) |
| GET | `/api/attendance/day?date=` (my team on a day; `day` null = not checked in) | signed in |
| GET | `/api/attendance/employees/{id}?from=&to=` | the person and those above them |
| GET | `/api/attendance/{id}/photo` (image/jpeg) | the person and those above them |

### Staff mail

One message, sent to each chosen employee as their own mail at their **office email** ("Dear Anu," or
"Dear All,"); `{name}` in the subject, greeting or text becomes the person's name. The portal calls `/send`
once per person (3 at a time) and shows each result on screen; nothing is stored. Reply-To is the sender's
office email. Brand admins can mail only their own brand's people.

| Method | Path | Who |
|---|---|---|
| GET | `/api/mail/setup` (`ready`, `from`, `fromName`) | owner, admin |
| POST | `/api/mail/send` `{employeeId, subject, greeting?, body}` → `{sent, error, email, at}` (200 even when it failed) | owner, admin |

Mail is sent through **Brevo** (`smtp-relay.brevo.com:587`, login `bf6436001@smtp-brevo.com`; free plan about
300 mails a day). Give the Brevo **SMTP key** as `MAIL_PASSWORD`; until then `/api/mail/setup` says not ready and the
portal shows mail as not set up. `MAIL_FROM` (default `info.amsidel@gmail.com`) must be a sender verified in Brevo
(Senders & Domains); an `@amsidel.com` address on a verified domain reaches inboxes best. `MAIL_HOST`, `MAIL_PORT`,
`MAIL_USERNAME` and `MAIL_FROM_NAME` switch to another provider without code changes.

### Leave

Leave year = January to December; Sundays are not counted (`app.leave.weekly-offs`). Standard types are created on
first start: CL 10, SL 10, EL 10, RH 5 (listed dates only) a year; CO and PL granted per person; LWP no limit.
A manager decides for employees of their brand, an admin for managers and employees, an owner for everyone;
nobody for themselves, and an owner's own leave is approved on submission.

| Method | Path | Who |
|---|---|---|
| GET | `/api/leave/types`, `/api/leave/restricted-holidays?year=` | signed in |
| POST, PUT | `/api/leave/types`, `/api/leave/types/{id}` | owner, admin |
| POST, DELETE | `/api/leave/restricted-holidays`, `/api/leave/restricted-holidays/{id}` | owner, admin |
| GET | `/api/leave/me/balances?year=`, `/api/leave/me/requests?year=`, `/api/leave/me/grants?year=` | signed in |
| POST | `/api/leave/me/requests` `{leaveTypeId, fromDate, toDate, halfDay, reason}` | signed in |
| POST | `/api/leave/me/requests/{id}/cancel` | the requester (pending, or approved and not started) |
| GET | `/api/leave/requests?status=&brandId=`, `/api/leave/team` | owner, admin, manager |
| POST | `/api/leave/requests/{id}/approve`, `/reject` `{note}` (note required to reject) | the person's approver |
| GET | `/api/leave/balances?employeeId=&year=`, `/api/leave/grants?employeeId=&year=` | owner, admin, manager |
| POST | `/api/leave/grants` `{employeeId, leaveTypeId, days, workedOn, reason}` | the person's approver |

Errors come back as `{timestamp, status, error, details?}`; `details` maps field names to messages.

Employee photos live in the web app (`amsidel-web/public/users/`); the database stores the path, e.g. `users/DW0001.jpg`.
