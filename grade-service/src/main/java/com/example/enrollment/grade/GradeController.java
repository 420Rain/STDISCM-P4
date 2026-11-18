package com.example.enrollment.grade;

import com.example.enrollment.common.TokenPrincipal;
import com.example.enrollment.common.TokenSupport;
import com.example.enrollment.grpc.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/grades")
public class GradeController {
    private static final Logger log = LoggerFactory.getLogger(GradeController.class);
    private final JdbcTemplate jdbc;
    private final CourseDirectoryGrpc.CourseDirectoryBlockingStub courseStub;
    private final EnrollmentDirectoryGrpc.EnrollmentDirectoryBlockingStub enrollmentStub;
    private final TransactionTemplate transaction;
    private final String jwtSecret;

    public GradeController(JdbcTemplate jdbc, CourseDirectoryGrpc.CourseDirectoryBlockingStub courseStub,
            EnrollmentDirectoryGrpc.EnrollmentDirectoryBlockingStub enrollmentStub, PlatformTransactionManager txManager,
            @Value("${app.jwt-secret}") String jwtSecret) {
        this.jdbc=jdbc; this.courseStub=courseStub; this.enrollmentStub=enrollmentStub;
        this.transaction = new TransactionTemplate(txManager);
        this.jwtSecret=jwtSecret;
    }
    public record GradeRequest(BigDecimal finalGrade) { }

    @GetMapping("/my")
    public List<Map<String,Object>> myGrades(HttpServletRequest request) {
        TokenPrincipal student = TokenSupport.requireRole(request, jwtSecret, "STUDENT");
        log.info("[DB SELECT] Student grade history studentId={}", student.userId());
        return jdbc.query("SELECT id,student_id,course_id,course_code,section_id,final_grade,updated_at FROM grades WHERE student_id=? ORDER BY updated_at DESC",
                (rs,row) -> Map.of("id",rs.getLong("id"),"studentId",rs.getLong("student_id"),"courseId",rs.getLong("course_id"),
                        "courseCode",rs.getString("course_code"),"sectionId",rs.getLong("section_id"),"finalGrade",rs.getBigDecimal("final_grade"),
                        "updatedAt",rs.getTimestamp("updated_at").toInstant().toString()), student.userId());
    }

    @PutMapping("/sections/{sectionId}/students/{studentId}")
    public Map<String,Object> updateFinalGrade(@PathVariable long sectionId, @PathVariable long studentId,
            @RequestBody GradeRequest body, HttpServletRequest request) {
        TokenPrincipal faculty = TokenSupport.requireRole(request, jwtSecret, "FACULTY");
        if (studentId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid student ID.");
        if (body == null || body.finalGrade()==null || body.finalGrade().compareTo(BigDecimal.ZERO)<0 || body.finalGrade().compareTo(new BigDecimal("100"))>0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Final grade must be a number from 0 to 100.");
        }

        SectionInfo section;
        try {
            log.info("[gRPC -> course-service] (IsFacultyAssigned) facultyId={} sectionId={}", faculty.userId(), sectionId);
            AssignmentResponse assignment = courseStub.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .isFacultyAssigned(FacultyAssignmentRequest.newBuilder().setFacultyUserId(faculty.userId()).setSectionId(sectionId).build());
            if (!assignment.getAssigned()) {
                log.warn("[AUTHZ DENY] Faculty {} attempted to edit section {} not assigned to them", faculty.userId(), sectionId);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only upload or update grades for sections assigned to you.");
            }
            log.info("[gRPC -> course-service] (GetSection) sectionId={}", sectionId);
            section = courseStub.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .getSection(SectionRequest.newBuilder().setSectionId(sectionId).build());
        } catch (StatusRuntimeException ex) {
            log.error("[gRPC ERROR] course-service (faculty assignment / GetSection) code={} description={}", ex.getStatus().getCode(), ex.getStatus().getDescription());
            if (ex.getStatus().getCode()==Status.Code.NOT_FOUND) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Section was not found.");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Course service is currently unavailable. Please try again later.");
        }

        // This is the missing authorization check: being assigned to a section does not authorize grading
        // every student. The student must have an ACTIVE enrollment in this exact section.
        try {
            log.info("[gRPC -> enrollment-service] (IsStudentEnrolled) studentId={} sectionId={}", studentId, sectionId);
            StudentEnrollmentResponse enrolled = enrollmentStub.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .isStudentEnrolled(StudentEnrollmentRequest.newBuilder().setStudentId(studentId).setSectionId(sectionId).build());
            if (!enrolled.getEnrolled()) {
                log.warn("[AUTHZ DENY] Grade rejected: studentId={} is not enrolled in sectionId={}", studentId, sectionId);
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Student " + studentId + " is not enrolled in " + section.getCourseCode() + " " + section.getSectionName() + ". No grade was saved.");
            }
        } catch (StatusRuntimeException ex) {
            log.error("[gRPC ERROR] enrollment-service (IsStudentEnrolled) code={} description={}", ex.getStatus().getCode(), ex.getStatus().getDescription());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Enrollment service is currently unavailable. The grade was not changed. Please try again later.");
        }

        try {
            transaction.execute(txStatus -> {
                log.info("[DB UPSERT] Saving final grade studentId={} courseCode={} section={} facultyId={}", studentId, section.getCourseCode(), section.getSectionName(), faculty.userId());
                jdbc.update("INSERT INTO grades(student_id,course_id,course_code,section_id,final_grade,updated_by) VALUES (?,?,?,?,?,?) ON CONFLICT(student_id,section_id) DO UPDATE SET final_grade=EXCLUDED.final_grade,course_code=EXCLUDED.course_code,updated_by=EXCLUDED.updated_by,updated_at=now()",
                        studentId, section.getCourseId(), section.getCourseCode(), sectionId, body.finalGrade(), faculty.userId());

                // Re-check while the grade write is still uncommitted. This closes the race where a
                // student unenrolls after the first check but before the grade INSERT/UPDATE.
                try {
                    log.info("[gRPC -> enrollment-service] (IsStudentEnrolled recheck before grade commit) studentId={} sectionId={}", studentId, sectionId);
                    StudentEnrollmentResponse finalCheck = enrollmentStub.withDeadlineAfter(2, TimeUnit.SECONDS)
                            .isStudentEnrolled(StudentEnrollmentRequest.newBuilder().setStudentId(studentId).setSectionId(sectionId).build());
                    if (!finalCheck.getEnrolled()) {
                        log.warn("[AUTHZ DENY] Enrollment ended while grade was being saved; rolling back studentId={} sectionId={}", studentId, sectionId);
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Student " + studentId + " is no longer enrolled in " + section.getCourseCode() + " " + section.getSectionName() + ". No grade was saved.");
                    }
                } catch (StatusRuntimeException ex) {
                    throw new EnrollmentCheckException(ex);
                }
                log.info("[DB COMMIT] Final grade saved studentId={} sectionId={}", studentId, sectionId);
                return null;
            });
        } catch (EnrollmentCheckException ex) {
            log.error("[gRPC ERROR] enrollment-service recheck failed; grade transaction rolled back", ex.grpc);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Enrollment service is currently unavailable. The grade was not changed. Please try again later.");
        } catch (Exception ex) {
            log.error("[DB ERROR] Grade transaction failed; returning an error instead of success", ex);
            throw ex;
        }
        return Map.of("message","Final grade saved.","studentId",studentId,"courseId",section.getCourseId(),"courseCode",section.getCourseCode(),
                "sectionId",sectionId,"sectionName",section.getSectionName(),"finalGrade",body.finalGrade());
    }

    private static class EnrollmentCheckException extends RuntimeException {
        private final StatusRuntimeException grpc;
        private EnrollmentCheckException(StatusRuntimeException grpc) { this.grpc = grpc; }
    }
}
