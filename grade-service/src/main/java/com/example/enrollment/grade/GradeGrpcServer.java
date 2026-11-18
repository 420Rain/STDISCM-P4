package com.example.enrollment.grade;

import com.example.enrollment.grpc.ActionResponse;
import com.example.enrollment.grpc.GradeDeletionRequest;
import com.example.enrollment.grpc.GradeRecordsGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GradeGrpcServer {
    private static final Logger log = LoggerFactory.getLogger(GradeGrpcServer.class);
    private final JdbcTemplate jdbc;
    private Server server;
    public GradeGrpcServer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void start() throws Exception {
        server = ServerBuilder.forPort(9092).addService(new GradeDirectory()).build().start();
        log.info("[gRPC SERVER] GradeRecords listening on port 9092");
    }
    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) { server.shutdown(); if (!server.awaitTermination(3, TimeUnit.SECONDS)) server.shutdownNow(); }
    }

    private class GradeDirectory extends GradeRecordsGrpc.GradeRecordsImplBase {
        @Override
        public void deleteGradeForUnenrollment(GradeDeletionRequest request, StreamObserver<ActionResponse> observer) {
            log.info("[gRPC <-> enrollment-service] (DeleteGradeForUnenrollment) studentId={} sectionId={}", request.getStudentId(), request.getSectionId());
            try {
                int deleted = jdbc.update("DELETE FROM grades WHERE student_id=? AND section_id=?", request.getStudentId(), request.getSectionId());
                log.info("[DB DELETE] Grade cleanup studentId={} sectionId={} deletedRows={}", request.getStudentId(), request.getSectionId(), deleted);
                observer.onNext(ActionResponse.newBuilder().setSuccess(true).setMessage("Grade removed if one existed.").build());
                observer.onCompleted();
            } catch (Exception ex) {
                log.error("[DB ERROR] Grade cleanup failed for studentId={} sectionId={}", request.getStudentId(), request.getSectionId(), ex);
                observer.onError(Status.UNAVAILABLE.withDescription("Grade record could not be removed.").asRuntimeException());
            }
        }
    }
}
