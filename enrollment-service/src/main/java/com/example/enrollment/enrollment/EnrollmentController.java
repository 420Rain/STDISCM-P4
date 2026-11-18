package com.example.enrollment.enrollment;

import com.example.enrollment.common.TokenPrincipal;
import com.example.enrollment.common.TokenSupport;
import com.example.enrollment.grpc.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentController.class);
    private static final int MAX_COURSES = 5;
    private final JdbcTemplate shard0, shard1;
    private final TransactionTemplate tx0, tx1;
    private final CourseDirectoryGrpc.CourseDirectoryBlockingStub courseStub;
    private final GradeRecordsGrpc.GradeRecordsBlockingStub gradeStub;
    private final String jwtSecret;

    public EnrollmentController(@Qualifier("shard0Jdbc") JdbcTemplate shard0, @Qualifier("shard1Jdbc") JdbcTemplate shard1,
            @Qualifier("shard0Tx") TransactionTemplate tx0, @Qualifier("shard1Tx") TransactionTemplate tx1,
            CourseDirectoryGrpc.CourseDirectoryBlockingStub courseStub,
            GradeRecordsGrpc.GradeRecordsBlockingStub gradeStub,
            @Value("${app.jwt-secret}") String jwtSecret) {
        this.shard0=shard0; this.shard1=shard1; this.tx0=tx0; this.tx1=tx1;
        this.courseStub=courseStub; this.gradeStub=gradeStub; this.jwtSecret=jwtSecret;
    }

    public record EnrollRequest(long courseId, long sectionId) { }

    @PostMapping
    public Map<String,Object> enroll(@RequestBody EnrollRequest request, HttpServletRequest httpRequest) {
        TokenPrincipal student = TokenSupport.requireRole(httpRequest, jwtSecret, "STUDENT");
        if (request.courseId() <= 0 || request.sectionId() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid course and section.");
        JdbcTemplate jdbc = shard(student.userId());
        TransactionTemplate tx = transaction(student.userId());
        log.info("[ENROLLMENT REQUEST] studentId={} courseId={} sectionId={} shard={}", student.userId(), request.courseId(), request.sectionId(), shardNumber(student.userId()));

        String requestId = UUID.randomUUID().toString();
        ReservationResponse reservation;
        try {
            log.info("[gRPC -> course-service] (ReserveSeat) requestId={} studentId={} sectionId={}", requestId, student.userId(), request.sectionId());
            reservation = courseStub.withDeadlineAfter(2, TimeUnit.SECONDS).reserveSeat(
                    EnrollmentReservationRequest.newBuilder().setRequestId(requestId).setStudentId(student.userId())
                            .setCourseId(request.courseId()).setSectionId(request.sectionId()).build());
        } catch (StatusRuntimeException ex) {
            log.error("[gRPC ERROR] course-service (ReserveSeat) code={} message={}", ex.getStatus().getCode(), ex.getStatus().getDescription());
            throw mapCourseError("Course service is unavailable. Enrollment was not processed. Please try again later.", ex);
        }
        if (!reservation.getAccepted()) throw new ResponseStatusException(HttpStatus.CONFLICT, reservation.getMessage());
        SectionInfo section = reservation.getSection();

        try {
            tx.execute(status -> {
                lockStudent(jdbc, student.userId());
                Integer activeCourses = jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE student_id=? AND status='ACTIVE'", Integer.class, student.userId());
                if (activeCourses != null && activeCourses >= MAX_COURSES) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "You have reached the 15-credit limit (5 courses × 3 credits). Unenroll from a course before adding another.");
                }
                log.info("[DB INSERT] PENDING enrollment studentId={} courseId={} sectionId={} shard={}", student.userId(), request.courseId(), request.sectionId(), shardNumber(student.userId()));
                jdbc.update("""
                        INSERT INTO enrollments(student_id,course_id,section_id,course_code,section_name,faculty_name,meeting_days,start_time,end_time,reservation_id,status)
                        VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')
                        """, student.userId(), request.courseId(), request.sectionId(), section.getCourseCode(), section.getSectionName(),
                        section.getFacultyName(), section.getMeetingDays(), section.getStartTime(), section.getEndTime(), UUID.fromString(requestId));
                try {
                    log.info("[gRPC -> course-service] (ConfirmReservation) requestId={}", requestId);
                    courseStub.withDeadlineAfter(2, TimeUnit.SECONDS).confirmReservation(ReservationIdRequest.newBuilder().setRequestId(requestId).build());
                } catch (StatusRuntimeException ex) { throw new CourseConfirmationException(ex); }
                jdbc.update("UPDATE enrollments SET status='ACTIVE' WHERE reservation_id=?", UUID.fromString(requestId));
                log.info("[DB COMMIT] Enrollment ACTIVE studentId={} courseCode={} section={} shard={}", student.userId(), section.getCourseCode(), section.getSectionName(), shardNumber(student.userId()));
                return null;
            });
        } catch (DuplicateKeyException ex) {
            log.warn("[DB CONSTRAINT] Duplicate enrollment studentId={} courseId={}", student.userId(), request.courseId());
            releaseReservation(requestId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already enrolled in this course. You can enroll in only one section of a course.");
        } catch (CourseConfirmationException ex) {
            log.error("[gRPC ERROR] course-service (ConfirmReservation) code={} message={}", ex.grpc.getStatus().getCode(), ex.grpc.getStatus().getDescription());
            releaseReservation(requestId);
            throw mapCourseError("Course service became unavailable before enrollment could be confirmed. No enrollment was saved. Please try again later.", ex.grpc);
        } catch (ResponseStatusException ex) {
            releaseReservation(requestId);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("[DB ERROR] Enrollment transaction rolled back requestId={}", requestId, ex);
            releaseReservation(requestId);
            throw ex;
        }
        return Map.of("message", "Enrollment successful.", "courseId", request.courseId(), "courseCode", section.getCourseCode(),
                "sectionId", request.sectionId(), "sectionName", section.getSectionName(), "reservationId", requestId,
                "creditsUsed", getActiveCount(student.userId()) * 3, "creditsLimit", 15);
    }

    @GetMapping("/my")
    public List<Map<String,Object>> myEnrollments(HttpServletRequest request) {
        TokenPrincipal student = TokenSupport.requireRole(request, jwtSecret, "STUDENT");
        log.info("[DB SELECT] Active enrollments studentId={} shard={}", student.userId(), shardNumber(student.userId()));
        return shard(student.userId()).query("""
                SELECT id,student_id,course_id,section_id,course_code,section_name,faculty_name,meeting_days,start_time,end_time,enrolled_at
                FROM enrollments WHERE student_id=? AND status='ACTIVE' ORDER BY enrolled_at DESC
                """, (rs,row) -> Map.ofEntries(
                Map.entry("id",rs.getLong("id")), Map.entry("studentId",rs.getLong("student_id")),
                Map.entry("courseId",rs.getLong("course_id")), Map.entry("sectionId",rs.getLong("section_id")),
                Map.entry("courseCode",rs.getString("course_code")), Map.entry("sectionName",rs.getString("section_name")),
                Map.entry("facultyName",rs.getString("faculty_name")), Map.entry("meetingDays",rs.getString("meeting_days")),
                Map.entry("startTime",rs.getString("start_time")), Map.entry("endTime",rs.getString("end_time")),
                Map.entry("enrolledAt",rs.getTimestamp("enrolled_at").toInstant().toString())), student.userId());
    }

    @GetMapping("/credits")
    public Map<String,Object> credits(HttpServletRequest request) {
        TokenPrincipal student = TokenSupport.requireRole(request, jwtSecret, "STUDENT");
        int count = getActiveCount(student.userId());
        return Map.of("coursesEnrolled",count,"creditsUsed",count*3,"creditLimit",15,"coursesAllowed",5,"creditsRemaining",Math.max(0,15-count*3));
    }

    @DeleteMapping("/{enrollmentId}")
    public Map<String,Object> unenroll(@PathVariable long enrollmentId, HttpServletRequest request) {
        TokenPrincipal student = TokenSupport.requireRole(request, jwtSecret, "STUDENT");
        JdbcTemplate jdbc = shard(student.userId());
        TransactionTemplate tx = transaction(student.userId());
        final EnrollmentRow[] removed = new EnrollmentRow[1];
        try {
            tx.execute(status -> {
                lockStudent(jdbc, student.userId());
                List<EnrollmentRow> rows = jdbc.query("SELECT id,student_id,course_id,section_id,reservation_id,course_code,section_name FROM enrollments WHERE id=? AND student_id=? AND status='ACTIVE' FOR UPDATE",
                        (rs,row) -> new EnrollmentRow(rs.getLong("id"),rs.getLong("student_id"),rs.getLong("course_id"),rs.getLong("section_id"),rs.getObject("reservation_id", UUID.class),rs.getString("course_code"),rs.getString("section_name")), enrollmentId, student.userId());
                if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That enrollment was not found or has already been removed.");
                EnrollmentRow row = rows.get(0);
                try {
                    log.info("[gRPC -> grade-service] (DeleteGradeForUnenrollment) studentId={} sectionId={}", student.userId(), row.sectionId());
                    ActionResponse gradeDeletion = gradeStub.withDeadlineAfter(2, TimeUnit.SECONDS).deleteGradeForUnenrollment(GradeDeletionRequest.newBuilder()
                            .setStudentId(student.userId()).setSectionId(row.sectionId()).build());
                    if (!gradeDeletion.getSuccess()) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Grade service could not remove the grade. The enrollment was not removed. Please try again later.");
                    }
                } catch (StatusRuntimeException ex) {
                    log.error("[gRPC ERROR] grade-service (DeleteGradeForUnenrollment) code={} message={}", ex.getStatus().getCode(), ex.getStatus().getDescription());
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Grade service is unavailable. The enrollment was not removed. Please try again later.");
                }
                int count = jdbc.update("DELETE FROM enrollments WHERE id=? AND student_id=?", enrollmentId, student.userId());
                if (count != 1) throw new IllegalStateException("Enrollment could not be removed.");
                removed[0] = row;
                log.info("[DB DELETE/COMMIT] Enrollment id={} studentId={} courseCode={} section={}", enrollmentId, student.userId(), row.courseCode(), row.sectionName());
                return null;
            });
        } catch (ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) {
            log.error("[DB ERROR] Unenrollment transaction failed enrollmentId={}", enrollmentId, ex);
            throw ex;
        }
        if (removed[0] != null) releaseConfirmedReservation(removed[0].reservationId());
        return Map.of("message", "You have unenrolled from " + removed[0].courseCode() + " " + removed[0].sectionName() + ". Any grade recorded for this section has also been removed.");
    }

    private void lockStudent(JdbcTemplate jdbc, long studentId) {
        jdbc.update("INSERT INTO enrollment_student_locks(student_id) VALUES (?) ON CONFLICT (student_id) DO NOTHING", studentId);
        jdbc.queryForObject("SELECT student_id FROM enrollment_student_locks WHERE student_id=? FOR UPDATE", Long.class, studentId);
    }
    private int getActiveCount(long studentId) {
        Integer count = shard(studentId).queryForObject("SELECT count(*) FROM enrollments WHERE student_id=? AND status='ACTIVE'", Integer.class, studentId);
        return count == null ? 0 : count;
    }
    private void releaseReservation(String requestId) {
        try {
            log.info("[gRPC -> course-service] (ReleaseReservation) requestId={}", requestId);
            courseStub.withDeadlineAfter(1, TimeUnit.SECONDS).releaseReservation(ReservationIdRequest.newBuilder().setRequestId(requestId).build());
        } catch (Exception ex) { log.warn("[gRPC WARN] Reservation release deferred requestId={}", requestId, ex); }
    }
    private void releaseConfirmedReservation(UUID requestId) {
        try {
            log.info("[gRPC -> course-service] (ReleaseReservation after unenroll) requestId={}", requestId);
            courseStub.withDeadlineAfter(1, TimeUnit.SECONDS).releaseReservation(ReservationIdRequest.newBuilder().setRequestId(requestId.toString()).build());
        } catch (Exception ex) { log.warn("[gRPC WARN] Capacity release deferred; course service may be offline reservationId={}", requestId, ex); }
    }
    private ResponseStatusException mapCourseError(String fallback, StatusRuntimeException ex) {
        HttpStatus status = switch (ex.getStatus().getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FAILED_PRECONDITION, RESOURCE_EXHAUSTED -> HttpStatus.CONFLICT;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String reason = ex.getStatus().getDescription();
        return new ResponseStatusException(status, reason == null || reason.isBlank() ? fallback : reason);
    }
    private JdbcTemplate shard(long studentId) { return shardNumber(studentId)==0 ? shard0 : shard1; }
    private TransactionTemplate transaction(long studentId) { return shardNumber(studentId)==0 ? tx0 : tx1; }
    private int shardNumber(long studentId) { return Math.floorMod(studentId, 2); }
    private record EnrollmentRow(long id,long studentId,long courseId,long sectionId,UUID reservationId,String courseCode,String sectionName) { }
    private static class CourseConfirmationException extends RuntimeException { final StatusRuntimeException grpc; CourseConfirmationException(StatusRuntimeException grpc){this.grpc=grpc;} }
}
