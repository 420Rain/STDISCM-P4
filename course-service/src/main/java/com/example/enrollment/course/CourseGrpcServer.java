package com.example.enrollment.course;

import com.example.enrollment.grpc.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class CourseGrpcServer {
    private static final Logger log = LoggerFactory.getLogger(CourseGrpcServer.class);
    private final JdbcTemplate jdbc; private final int port; private final TransactionTemplate tx;
    private Server server;

    public CourseGrpcServer(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            @Value("${grpc.server.port:9090}") int port) {
        this.jdbc = jdbc; this.port = port; this.tx = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public void start() throws Exception {
        server = ServerBuilder.forPort(port).addService(new Directory()).build().start();
        log.info("gRPC server listening on port {}", port);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) { server.shutdown(); if (!server.awaitTermination(3, TimeUnit.SECONDS)) server.shutdownNow(); }
    }

    private SectionRow section(long sectionId) {
        log.info("[DB SELECT] Section details sectionId={}", sectionId);
        List<SectionRow> rows = jdbc.query("SELECT s.id section_id,s.course_id,c.code,c.title,s.section_name,s.faculty_user_id,s.faculty_name,s.meeting_days,s.start_time,s.end_time,s.enrollment_open,s.capacity FROM sections s JOIN courses c ON c.id=s.course_id WHERE s.id=?",
                (rs,row) -> new SectionRow(rs.getLong("section_id"),rs.getLong("course_id"),rs.getString("code"),rs.getString("title"),rs.getString("section_name"),rs.getLong("faculty_user_id"),rs.getString("faculty_name"),rs.getString("meeting_days"),rs.getString("start_time"),rs.getString("end_time"),rs.getBoolean("enrollment_open"),rs.getInt("capacity")), sectionId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Section was not found.");
        return rows.get(0);
    }

    private SectionInfo toProto(SectionRow r) {
        return SectionInfo.newBuilder().setSectionId(r.sectionId()).setCourseId(r.courseId()).setCourseCode(r.code()).setCourseTitle(r.title())
                .setSectionName(r.sectionName()).setFacultyUserId(r.facultyId()).setFacultyName(r.facultyName())
                .setMeetingDays(r.meetingDays()).setStartTime(r.startTime()).setEndTime(r.endTime())
                .setEnrollmentOpen(r.open()).setCapacity(r.capacity()).build();
    }

    private class Directory extends CourseDirectoryGrpc.CourseDirectoryImplBase {
        @Override public void getSection(SectionRequest request, StreamObserver<SectionInfo> observer) {
            log.info("[gRPC <-> enrollment/grade-service] (GetSection) sectionId={}", request.getSectionId());
            try { observer.onNext(toProto(section(request.getSectionId()))); observer.onCompleted(); }
            catch (IllegalArgumentException ex) { observer.onError(Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException()); }
            catch (Exception ex) { log.error("DB failure in GetSection", ex); observer.onError(Status.UNAVAILABLE.withDescription("Course database is unavailable.").asRuntimeException()); }
        }

        @Override public void reserveSeat(EnrollmentReservationRequest request, StreamObserver<ReservationResponse> observer) {
            log.info("[gRPC <-> enrollment-service] (ReserveSeat) requestId={} studentId={} sectionId={}", request.getRequestId(), request.getStudentId(), request.getSectionId());
            try {
                ReservationResponse response = tx.execute(status -> {
                    SectionRow section = sectionForUpdate(request.getSectionId());
                    if (section.courseId() != request.getCourseId()) throw new IllegalArgumentException("The selected section does not belong to this course.");
                    if (!section.open()) throw new IllegalStateException("Enrollment is closed for this section.");
                    log.info("[DB SELECT] Active seat reservations sectionId={}", request.getSectionId());
                    Integer active = jdbc.queryForObject("SELECT count(*) FROM enrollment_reservations WHERE section_id=? AND (status='CONFIRMED' OR (status='PENDING' AND expires_at > now()))",
                            Integer.class, request.getSectionId());
                    if (active != null && active >= section.capacity()) throw new IllegalStateException("This section is already full.");
                    UUID id = UUID.fromString(request.getRequestId());
                    int inserted = jdbc.update("INSERT INTO enrollment_reservations(request_id,student_id,course_id,section_id,status,expires_at) VALUES (?,?,?,?,'PENDING',?) ON CONFLICT (request_id) DO NOTHING",
                            id, request.getStudentId(), request.getCourseId(), request.getSectionId(), Timestamp.from(Instant.now().plusSeconds(120)));
                    log.info("[DB INSERT] Seat reservation requestId={} sectionId={} insertedRows={}", request.getRequestId(), request.getSectionId(), inserted);
                    return ReservationResponse.newBuilder().setRequestId(request.getRequestId()).setAccepted(true).setMessage("Seat temporarily reserved.").setSection(toProto(section)).build();
                });
                observer.onNext(response); observer.onCompleted();
            } catch (IllegalStateException ex) { observer.onError(Status.FAILED_PRECONDITION.withDescription(ex.getMessage()).asRuntimeException()); }
              catch (IllegalArgumentException ex) { observer.onError(Status.NOT_FOUND.withDescription(ex.getMessage()).asRuntimeException()); }
              catch (Exception ex) { log.error("[DB ERROR] ReserveSeat could not reserve capacity", ex); observer.onError(Status.UNAVAILABLE.withDescription("Course service could not reserve this section.").asRuntimeException()); }
        }

        @Override public void confirmReservation(ReservationIdRequest request, StreamObserver<ReservationResponse> observer) {
            log.info("[gRPC <-> enrollment-service] (ConfirmReservation) requestId={}", request.getRequestId());
            try {
                ReservationResponse response = tx.execute(status -> {
                    int changed = jdbc.update("UPDATE enrollment_reservations SET status='CONFIRMED',expires_at=NULL WHERE request_id=? AND status='PENDING' AND expires_at > now()", UUID.fromString(request.getRequestId()));
                    log.info("[DB UPDATE] Reservation confirmed requestId={} changedRows={}", request.getRequestId(), changed);
                    if (changed == 0) {
                        Integer count = jdbc.queryForObject("SELECT count(*) FROM enrollment_reservations WHERE request_id=? AND status='CONFIRMED'", Integer.class, UUID.fromString(request.getRequestId()));
                        if (count == null || count == 0) throw new IllegalStateException("Reservation expired or no longer exists.");
                    }
                    return ReservationResponse.newBuilder().setRequestId(request.getRequestId()).setAccepted(true).setMessage("Reservation confirmed.").build();
                });
                observer.onNext(response); observer.onCompleted();
            } catch (IllegalStateException | IllegalArgumentException ex) { observer.onError(Status.FAILED_PRECONDITION.withDescription(ex.getMessage()).asRuntimeException()); }
              catch (Exception ex) { log.error("[DB ERROR] ConfirmReservation failed", ex); observer.onError(Status.UNAVAILABLE.withDescription("Course service could not confirm the reservation.").asRuntimeException()); }
        }

        @Override public void releaseReservation(ReservationIdRequest request, StreamObserver<ActionResponse> observer) {
            log.info("[gRPC <-> enrollment-service] (ReleaseReservation) requestId={}", request.getRequestId());
            try {
                int changed = jdbc.update("UPDATE enrollment_reservations SET status='RELEASED' WHERE request_id=?", UUID.fromString(request.getRequestId()));
                log.info("[DB UPDATE] Reservation released requestId={} changedRows={}", request.getRequestId(), changed);
                observer.onNext(ActionResponse.newBuilder().setSuccess(true).setMessage("Reservation released when applicable.").build()); observer.onCompleted(); }
            catch (Exception ex) { log.warn("Could not release reservation {}", request.getRequestId(), ex); observer.onError(Status.UNAVAILABLE.withDescription("Could not release reservation.").asRuntimeException()); }
        }

        @Override public void isFacultyAssigned(FacultyAssignmentRequest request, StreamObserver<AssignmentResponse> observer) {
            log.info("[gRPC <-> grade-service] (IsFacultyAssigned) facultyId={} sectionId={}", request.getFacultyUserId(), request.getSectionId());
            try {
                log.info("[DB SELECT] Faculty assignment facultyId={} sectionId={}", request.getFacultyUserId(), request.getSectionId());
                Integer count = jdbc.queryForObject("SELECT count(*) FROM sections WHERE id=? AND faculty_user_id=?", Integer.class, request.getSectionId(), request.getFacultyUserId());
                observer.onNext(AssignmentResponse.newBuilder().setAssigned(count != null && count > 0).build()); observer.onCompleted();
            } catch (Exception ex) { log.error("DB failure in IsFacultyAssigned facultyId={} sectionId={}", request.getFacultyUserId(), request.getSectionId(), ex); observer.onError(Status.UNAVAILABLE.withDescription("Could not verify faculty assignment.").asRuntimeException()); }
        }
    }

    private SectionRow sectionForUpdate(long id) {
        List<SectionRow> rows = jdbc.query("SELECT s.id section_id,s.course_id,c.code,c.title,s.section_name,s.faculty_user_id,s.faculty_name,s.meeting_days,s.start_time,s.end_time,s.enrollment_open,s.capacity FROM sections s JOIN courses c ON c.id=s.course_id WHERE s.id=? FOR UPDATE OF s",
                (rs,row) -> new SectionRow(rs.getLong("section_id"),rs.getLong("course_id"),rs.getString("code"),rs.getString("title"),rs.getString("section_name"),rs.getLong("faculty_user_id"),rs.getString("faculty_name"),rs.getString("meeting_days"),rs.getString("start_time"),rs.getString("end_time"),rs.getBoolean("enrollment_open"),rs.getInt("capacity")), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Section was not found.");
        return rows.get(0);
    }
    private record SectionRow(long sectionId,long courseId,String code,String title,String sectionName,long facultyId,String facultyName,String meetingDays,String startTime,String endTime,boolean open,int capacity) { }
}
