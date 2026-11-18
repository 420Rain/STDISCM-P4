# Distributed Online Enrollment System

A teaching/demo implementation of a distributed university enrollment system. The browser UI runs in its own container. Four Spring Boot services run separately and communicate through REST at the browser edge and gRPC between backend nodes. PostgreSQL stores the data, with streaming replication to a standby and two logical enrollment shards.

> **Scope note:** This is a runnable classroom starter, not a production-ready university SIS. The database standby is promoted manually; the demo uses plaintext gRPC inside a private Docker network; JWT access tokens are stateless and remain usable until their short expiration even after logout. The operational boundaries and limitations are described below.

## 1. Features implemented

### Student
- Register a student account with username, email, and password.
- Sign in using username/email and password.
- Optional sign-in with Google (requires a Google OAuth Web client ID).
- View courses and their sections, including whether enrollment is open.
- Enroll in an open section (maximum 5 courses / 15 credits; each course counts as 3 credits).
- Track used and remaining credits.
- Unenroll from an active course after confirmation; any grade attached to that section is deleted and the seat is released.
- View personal enrollment records, course code, assigned faculty, section and class time.
- View previous/final grades by course code.

### Faculty
- Sign in using a seeded faculty account (or a pre-provisioned account linked to a Google email).
- View only sections assigned to that faculty member.
- Upload or update a student's overall/final grade for an assigned section, but only when that student is actively enrolled in that exact section.

### Cross-cutting requirements
- JWT access tokens are checked locally by every backend service. An auth-service outage does not require other services to call auth-service to validate an already-issued, unexpired JWT.
- Access JWT expiration is enforced at every service. The demo access-token lifetime is 10 minutes.
- Refresh sessions are persisted in `auth_db`; refresh tokens are stored as SHA-256 hashes and rotated on refresh. The session database survives normal container restarts through the PostgreSQL volume.
- Auth role rules: students cannot write grades; faculty cannot use student-only endpoints; faculty grade writes require a gRPC check that the faculty member owns the selected section.
- A student can enroll in a course only once, even if the course has multiple sections. A database unique constraint on `(student_id, course_id)` is the final concurrency guard.
- A student is limited to five active courses (15 credits). A per-student lock row serializes concurrent enrollment requests before the backend checks the credit count.
- Unenrollment removes the active enrollment, requests grade deletion through gRPC, then releases the confirmed course seat. If the Grade Service is unavailable, the unenrollment is rejected rather than leaving a grade behind.
- Grade writes validate both the faculty assignment and the student's active enrollment in the exact section through gRPC. A student enrolled in CS101 S1 cannot be graded by the faculty assigned to CS101 S2. The enrollment check is repeated inside the grade-write transaction so a concurrent unenrollment cannot leave a newly committed grade behind.
- Enrollment uses a gRPC seat-reservation/confirmation flow. A failed gRPC check or confirmation returns an error and rolls back the local enrollment transaction. Unconfirmed reservations expire after two minutes.
- Backend database operations use HikariCP connection pools.
- Enrollment records are horizontally partitioned across two logical PostgreSQL databases using `student_id % 2`.
- PostgreSQL primary/standby streaming replication provides a redundant persistence copy. Promotion is a manual operation included in `scripts/promote-standby.sh`.
- Logs are emitted by each service with operation prefixes such as `[DB SELECT]`, `[DB INSERT]`, `[DB UPDATE]`, `[DB COMMIT]`, `[DB ERROR]`, `[gRPC -> course-service] (ReserveSeat)`, and `[gRPC <-> enrollment-service] (IsStudentEnrolled)`. The web UI displays service error messages rather than silently treating failed operations as successful.
- Each dashboard section shows its service online/offline status. The frontend polls the four health endpoints every six seconds and combines failures into one notification naming the unavailable services.

## 2. Architecture

```text
Browser
   |
   | HTTP (same origin)
   v
[web container: React build + Nginx reverse proxy]
   |             |               |                 |
   v             v               v                 v
[auth]        [course]       [enrollment]         [grade]
 Spring        Spring          Spring              Spring
 Boot          Boot            Boot                Boot
   |             |               |                 |
   |             |<--- gRPC ---->|                 |
   |             |<-------------- gRPC ------------|
   |             |               |                 |
   v             v               v                 v
 auth_db      course_db      enrollment_shard_0/1  grade_db
   \              \             /                  /
    \_____________ PostgreSQL primary ____________/
                         |
                  WAL streaming replication
                         |
                 PostgreSQL standby
```

### Nodes / containers

| Container | Responsibility | Host port |
|---|---|---:|
| `web` | React website and Nginx reverse proxy | 8080 |
| `auth-service` | Registration, password/Google sign-in, JWTs, refresh sessions | 8081 |
| `course-service` | Course catalog, sections, faculty assignments; gRPC server | 8082, gRPC 9090 |
| `enrollment-service` | Student enrollments, sharding, gRPC server/client | 8083, internal gRPC 9091 |
| `grade-service` | Student grade reads, enrollment-checked faculty grade updates, grade deletion gRPC | 8084, internal gRPC 9092 |
| `postgres-primary` | Writable PostgreSQL node | 5432 |
| `postgres-standby` | Streaming replica / hot standby | 5433 |

Each container is attached to the `enrollment-net` Docker bridge network and receives its own container IP. Services use Docker DNS names (`course-service`, `postgres-primary`) instead of hard-coded container IP addresses. Nginx uses Docker’s embedded DNS resolver so a restarted backend can be found at its new container IP. To inspect assigned IPs:

```bash
docker network ls
docker network inspect enrollment-distributed_enrollment-net
docker inspect "$(docker compose ps -q web)" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
```

The exact Compose network/container names can vary with the directory name and Compose version; use `docker compose ps` and `docker ps` to see the names on your machine.

### gRPC communication

`common/src/main/proto/enrollment.proto` defines the `CourseDirectory` service. The course node hosts the `CourseDirectory` gRPC server. Enrollment calls `ReserveSeat`, `ConfirmReservation`, and `ReleaseReservation`; grade calls `IsFacultyAssigned` and `GetSection`. Enrollment also exposes `EnrollmentDirectory` on internal port 9091 so grade-service can verify the student is enrolled in that exact section. Grade-service exposes `GradeRecords` on internal port 9092 so enrollment-service can delete a section grade during unenrollment. The generated Java stubs are created during Maven compilation.

The browser does not speak gRPC. It calls the Nginx reverse proxy over HTTP; Nginx forwards `/api/auth`, `/api/courses`, `/api/enrollments`, and `/api/grades` to their corresponding Spring Boot containers.

## 3. Requirements and important design decisions

### Authentication and session persistence
- Auth issues a signed HS256 JWT with `userId`, `username`, `role`, `jti`, and expiry.
- All services share the same `JWT_SECRET` and validate the signature and expiry locally. They do not make a request to auth-service for every API call.
- The access JWT lives for 10 minutes by default. Refresh sessions live for 7 days by default and are persisted in `auth_sessions`.
- The React client stores its current auth bundle in browser `sessionStorage` (tab-scoped). It persists across reloads in that tab but a separate tab can sign in as a different user without replacing the first tab's auth state. The refresh token is rotated each time it is used. The dashboard shows the Google profile name (`full_name`) rather than the generated login username.
- When auth-service is down, an existing, unexpired access JWT still works for APIs on the other backend services. New login and token refresh are unavailable until auth-service returns.
- Logout revokes the persisted refresh session and removes the browser's local tokens. A copied access JWT cannot be instantly revoked by a stateless verifier; it remains valid at other services only until its 10-minute expiry. This is the deliberate trade-off that allows service-local JWT verification while auth-service is unavailable.
- Google sign-in verifies the Google ID token with Google's `tokeninfo` endpoint and checks the `aud` against `GOOGLE_CLIENT_ID` and `email_verified`. A previously unseen verified Google email is provisioned as a STUDENT account. Existing accounts retain their assigned role.

### Enrollment: reservation, confirmation, and duplicate protection
1. Enrollment validates that the caller's JWT role is `STUDENT`.
2. Enrollment asks course-service over gRPC to reserve an open seat. If course-service is unavailable, times out, the section is closed, or the section is full, no enrollment row is written.
3. Enrollment opens a transaction on the student's shard and inserts a `PENDING` row.
4. While that transaction is open, enrollment asks course-service to confirm the reservation.
5. If confirmation fails or times out, the local transaction rolls back. A best-effort release is sent; a PENDING reservation also expires after two minutes.
6. If confirmation succeeds, the enrollment row is changed to `ACTIVE` and committed.
7. A unique constraint on `(student_id, course_id)` ensures simultaneous requests/double-clicks cannot create two active enrollments in different sections of the same course. One request succeeds; a competing request receives HTTP 409.

This is a small saga-style coordination pattern, not a general distributed transaction protocol. There is no claim of exactly-once execution across arbitrary network partitions. The database constraint protects the enrollment record; the expiring reservation limits stale seat holds. For a production system, add a durable outbox/reconciliation worker and idempotency keys supplied by the browser.

### Database persistence, replication, and sharding
- One PostgreSQL primary cluster holds separate databases: `auth_db`, `course_db`, `grade_db`, `enrollment_shard_0`, and `enrollment_shard_1`.
- Enrollment writes and reads route by `Math.floorMod(studentId, 2)`. That puts all records for one student on the same shard and makes the unique `(student_id, course_id)` rule reliable within that student's shard.
- Both enrollment databases are separate logical databases but share the same PostgreSQL primary/standby pair in this educational Docker setup. This demonstrates data partitioning without requiring more database containers. A production scaling deployment would move each shard to its own PostgreSQL cluster.
- The standby uses PostgreSQL WAL streaming replication. It is read-only until promoted. The replica is asynchronous by default, so a sudden primary loss can lose the latest transactions that had not reached the standby. This setup demonstrates redundancy and manual recovery, not zero-RPO automatic HA.
- HikariCP pools are configured individually for auth, course, grade, and both enrollment shards.

### Service failure behavior

| Failed node | Expected impact | Services that can continue |
|---|---|---|
| Auth | Login, register, logout, refresh unavailable | Existing valid JWT calls to course/enrollment/grade |
| Course | Course catalog unavailable; new enrollments and faculty-assignment checks fail closed | Auth, enrollment history reads, student grade reads |
| Enrollment | Enrollments and enrollment history unavailable; grade writes fail closed because active enrollment cannot be verified | Auth, catalog, student grade reads |
| Grade | Grade reads and grade updates unavailable | Auth, catalog, enrollment |
| Web | Website unavailable | Backend APIs remain up and can be tested directly |
| PostgreSQL primary | Writes/DB-backed reads fail until standby promotion and service reconnect | Services are running but cannot use their database until recovery |

A backend service returns a useful HTTP error when a required dependency is unavailable. For example, enrollment does not silently continue if course-service cannot reserve/confirm a seat. The frontend renders the returned error text in a toast, deduplicates dashboard failures by service, and polls health status every six seconds to show Online / Offline indicators next to the relevant sections.

## 4. Build and run with Docker (recommended)

### Prerequisites
- Docker Desktop with Docker Compose v2, or Docker Engine plus the Compose plugin.
- At least 6 GB RAM available to Docker is recommended for building all images.
- Internet access for the initial image and Maven/npm dependency downloads.

### Step 1 — Get the project and configure environment

From the project root, create the environment file. On macOS/Linux/WSL/Git Bash:

```bash
cp .env.example .env
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env` and set a private random `JWT_SECRET` containing at least 32 characters. Keep it identical for every backend service (Compose passes the same value to all four services).

Google sign-in is optional. To enable it:
1. Create an OAuth 2.0 **Web application** client in Google Cloud Console.
2. Add `http://localhost:8080` to its Authorized JavaScript origins.
3. Put the client ID in `.env` as `GOOGLE_CLIENT_ID=...`.
4. Rebuild the web and auth containers after changing it.

Without a Google client ID, the Google option remains visibly disabled. Username/password sign-in still works.

### Step 2 — Build and start all containers

```bash
docker compose up --build -d
```

The first build may take several minutes because Docker builds the Spring Boot modules and the React bundle. The primary database initializes the application databases and replication user. The standby takes a base backup and starts streaming from the primary.

Check container status:

```bash
docker compose ps
```

Watch service logs:

```bash
docker compose logs -f auth-service course-service enrollment-service grade-service
```

Open the web application:

```text
http://localhost:8080
```

### Step 3 — Sign in with the demo accounts

The auth service inserts the following accounts when `auth_db` is first initialized:

| Role | Username | Password | User ID |
|---|---|---|---:|
| Student | `student1` | `Student123!` | 1 |
| Faculty | `faculty1` | `Faculty123!` | 2 |
| Faculty | `faculty2` | `Faculty123!` | 3 |
| Faculty | `faculty3` | `Faculty123!` | 4 |
| Student | `student2` | `Student123!` | 5 |

Sample courses and section assignments:

| Course | Course ID | Section | Section ID | Assigned faculty | Schedule |
|---|---:|---|---:|---|---|
| Introduction to Computing (CS101) | 1001 | S1 | 101 | Alex Morgan (`faculty1`) | [Monday \| Thursday] 12:45–14:15 |
| Introduction to Computing (CS101) | 1001 | S2 | 102 | Jamie Reyes (`faculty2`) | [Tuesday \| Friday] 09:00–10:30 |
| Discrete Mathematics (MATH201) | 1002 | S1 | 201 | Taylor Santos (`faculty3`) | [Monday \| Wednesday] 14:30–16:00 |

The course/section metadata is also migrated for existing Docker volumes created by the previous project version. Demo IDs and seed accounts assume the original sample seed records are present.

### Step 4 — Try the required operations

**Student flow**
1. Sign in as `student1`.
2. View the catalog, faculty names, class schedules, capacity, and slots left.
3. Enroll in CS101 S1.
4. Try enrolling in CS101 S2 as well. The system must reject the second attempt because a student may take a course only once, regardless of section.
5. Confirm the credit tracker changes to 3 / 15 credits.

**Faculty flow**
1. Sign out and sign in as `faculty1`.
2. The assigned section should be CS101 / S1 (section 101).
3. Enter student ID `1` and a final grade such as `95.00`, then save.
4. While student1 is enrolled in CS101 S1, sign in as `faculty2` (assigned to CS101 S2) and try to upload a grade for student ID 1 in section 102. The request must be rejected with HTTP 409 because the student is not enrolled in S2.
5. Sign out and sign in as `faculty2`; trying to update section 101 must return HTTP 403 because that section belongs to `faculty1`.
6. Sign in as `student1`; the backend rejects a direct grade write with HTTP 403 and a readable JSON `message` field.
7. Open a second browser tab and sign in there with a different role. The first tab should keep its own account because the token bundle is held in tab-scoped `sessionStorage`.

**Unenrollment flow**
1. Return to the `student1` tab and unenroll from CS101 S1. Confirm the browser prompt.
2. The enrollment should disappear, any final grade for that section should be removed, and the seat count should increase. The toast confirms the operation.
3. Try uploading a grade for student ID 1 in section 101 again; it must now be rejected because the student is no longer enrolled.

**Other auth checks**
- Register a new student from the sign-in page.
- Use an expired JWT (wait more than 10 minutes without refreshing) and make a protected API request; the service returns HTTP 401.
- Stop auth-service after obtaining an access token; course/enrollment/grade calls using that still-unexpired token continue to work. Login and refresh do not.

### Step 5 — Inspect service and database logs

```bash
docker compose logs -f enrollment-service course-service
docker compose logs -f grade-service course-service
docker compose logs -f auth-service
```

Look for messages such as `[gRPC -> course-service] (ReserveSeat)`, `[gRPC -> course-service] (ConfirmReservation)`, `[gRPC -> enrollment-service] (IsStudentEnrolled)`, `[DB INSERT]`, `[DB COMMIT]`, `[DB UPSERT]`, and `[gRPC ERROR]` when a dependency fails.

### Step 6 — Test service fault tolerance

Use a second browser tab or keep a valid logged-in session open. Stop one service at a time:

```bash
docker compose stop grade-service
```

Expected: grade read/write fails with a visible error; catalog and enrollment remain usable. Restart it:

```bash
docker compose start grade-service
```

Repeat with the other service nodes:

```bash
docker compose stop enrollment-service
docker compose start enrollment-service

docker compose stop course-service
docker compose start course-service

docker compose stop auth-service
# Verify that the existing access JWT still works for other services.
docker compose start auth-service
```

If `course-service` is stopped, the catalog fails, new enrollments fail closed, and faculty grade uploads fail because the grade service cannot verify the section assignment. Existing enrollment-history and student grade reads remain available because they are served by their own services.

To inspect a request during a failure, watch the responsible service's logs. The UI should show the API's `message` field rather than a success state. The interface polls `/status/auth`, `/status/courses`, `/status/enrollments`, and `/status/grades` every six seconds and displays Online / Offline badges beside the corresponding page sections.

### Step 7 — Test database redundancy (manual promotion)

Confirm that the standby is following the primary:

```bash
docker compose exec postgres-primary psql -U postgres -c "SELECT application_name, state, sync_state FROM pg_stat_replication;"
docker compose exec postgres-standby psql -U postgres -c "SELECT pg_is_in_recovery();"
```

The standby query should return `true` before promotion. If the primary is unavailable and you want to demonstrate recovery, run:

```bash
./scripts/promote-standby.sh
```

The script promotes the standby, stops the old primary to prevent split-brain, changes `DB_HOST` in `.env` to `postgres-standby`, and recreates backend containers without dependency startup checks. On Windows, run the script from WSL or Git Bash; alternatively, issue these commands manually from PowerShell (then edit `.env` so `DB_HOST=postgres-standby`):

```powershell
docker compose exec postgres-standby pg_ctl -D /var/lib/postgresql/data promote
docker compose stop postgres-primary
docker compose up -d --no-deps auth-service course-service enrollment-service grade-service
```

Verify that APIs recover:

```bash
docker compose ps
docker compose logs --tail=100 auth-service course-service enrollment-service grade-service
```

**Important:** This is a one-way demo failover. Do not start the old primary as a writable database after promotion. Rebuild/re-seed it from the promoted primary before attempting failback. This sample does not include Patroni, automatic leader election, or zero-downtime failover.

## 5. Build and run locally without Docker (optional)

Prerequisites: JDK 21, Maven 3.9+, Node.js 22+, npm, and a PostgreSQL instance with the same databases/credentials defined above.

Compile the backend (this also generates Java gRPC code from the `.proto` definition):

```bash
mvn -B -DskipTests package
```

Run each backend in a separate terminal from the project root:

```bash
java -jar auth-service/target/auth-service-1.0.0.jar
java -jar course-service/target/course-service-1.0.0.jar
java -jar enrollment-service/target/enrollment-service-1.0.0.jar
java -jar grade-service/target/grade-service-1.0.0.jar
```

Set `DB_HOST`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, and (for enrollment/grade) `COURSE_GRPC_HOST` / `COURSE_GRPC_PORT` in each terminal's environment. The course gRPC server listens on 9090.

Build the frontend:

```bash
cd web
npm install
npm run build
npm run dev
```

The Vite dev server is configured for `localhost:5173`, but the Nginx proxy is only included in Docker. For local frontend development, add a Vite proxy in `vite.config.js` or use the Docker web container.

## 6. API summary

All protected endpoints require `Authorization: Bearer <accessToken>`.

| Method | Endpoint | Rule |
|---|---|---|
| POST | `/api/auth/register` | Public; creates STUDENT account |
| POST | `/api/auth/login` | Public; username/email and password |
| POST | `/api/auth/google` | Public; Google ID token credential |
| POST | `/api/auth/refresh` | Public; rotates persisted refresh session |
| POST | `/api/auth/logout` | Authenticated; revokes refresh session |
| GET | `/api/courses` | Authenticated student or faculty |
| GET | `/api/courses/my-sections` | FACULTY only |
| POST | `/api/enrollments` | STUDENT only; JSON `{ "courseId": 1001, "sectionId": 101 }` |
| GET | `/api/enrollments/my` | STUDENT only |
| GET | `/api/enrollments/credits` | STUDENT only; current credits and remaining limit |
| DELETE | `/api/enrollments/{enrollmentId}` | STUDENT only; removes enrollment, grade, and releases reserved seat |
| GET | `/api/grades/my` | STUDENT only |
| PUT | `/api/grades/sections/{sectionId}/students/{studentId}` | FACULTY only; JSON `{ "finalGrade": 95.00 }`; assigned faculty only |

HTTP 401 means the token is missing, invalid, or expired. HTTP 403 means the authenticated role or assignment is not allowed. HTTP 409 means a business conflict such as a duplicate enrollment, closed/full section, the 15-credit limit, or an attempted grade update for a student not enrolled in that exact section. HTTP 503 means a required remote dependency is unavailable.

## 7. Clean reset

To remove all persisted demo data and recreate a clean database, first ensure `.env` has `DB_HOST=postgres-primary` (especially after a failover), then run:

```bash
docker compose down -v
docker compose up --build -d
```

`down -v` deletes both PostgreSQL volumes, including users, sessions, grades, courses, enrollment records, and replication state. Do not run it when you need to preserve data.

## 8. Repository layout

```text
distributed-enrollment/
├── auth-service/          # Spring Boot auth API, persisted refresh sessions
├── course-service/        # Spring Boot course API + gRPC server
├── enrollment-service/    # Spring Boot enrollment API + two DB shards + gRPC client
├── grade-service/         # Spring Boot grade API + gRPC client
├── common/                # Shared JWT/error helpers and enrollment.proto
├── web/                   # React + Vite frontend, packaged behind Nginx
├── infra/db/init/         # PostgreSQL primary initialization
├── infra/db/standby-entrypoint.sh
├── infra/nginx.conf       # HTTP reverse proxy to the backend containers
├── scripts/               # Manual database promotion helper
├── slides/                # Editable implementation presentation
├── docker-compose.yml
├── pom.xml
└── README.md
```
