package com.example.enrollment.enrollment;

import com.example.enrollment.grpc.EnrollmentDirectoryGrpc;
import com.example.enrollment.grpc.StudentEnrollmentRequest;
import com.example.enrollment.grpc.StudentEnrollmentResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentDirectoryGrpcServer {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentDirectoryGrpcServer.class);
    private final JdbcTemplate shard0, shard1;
    private Server server;

    public EnrollmentDirectoryGrpcServer(@Qualifier("shard0Jdbc") JdbcTemplate shard0, @Qualifier("shard1Jdbc") JdbcTemplate shard1) {
        this.shard0 = shard0; this.shard1 = shard1;
    }

    @PostConstruct
    public void start() throws Exception {
        server = ServerBuilder.forPort(9091).addService(new Directory()).build().start();
        log.info("[gRPC SERVER] EnrollmentDirectory listening on port 9091");
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) { server.shutdown(); if (!server.awaitTermination(3, TimeUnit.SECONDS)) server.shutdownNow(); }
    }

    private class Directory extends EnrollmentDirectoryGrpc.EnrollmentDirectoryImplBase {
        @Override
        public void isStudentEnrolled(StudentEnrollmentRequest request, StreamObserver<StudentEnrollmentResponse> observer) {
            log.info("[gRPC <-> grade-service] (IsStudentEnrolled) studentId={} sectionId={}", request.getStudentId(), request.getSectionId());
            try {
                JdbcTemplate jdbc = Math.floorMod(request.getStudentId(), 2) == 0 ? shard0 : shard1;
                Integer count = jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE student_id=? AND section_id=? AND status='ACTIVE'",
                        Integer.class, request.getStudentId(), request.getSectionId());
                log.info("[DB SELECT] Enrollment lookup studentId={} sectionId={} active={}", request.getStudentId(), request.getSectionId(), count);
                observer.onNext(StudentEnrollmentResponse.newBuilder().setEnrolled(count != null && count > 0).build());
                observer.onCompleted();
            } catch (Exception ex) {
                log.error("[DB ERROR] IsStudentEnrolled lookup failed", ex);
                observer.onError(Status.UNAVAILABLE.withDescription("Enrollment records could not be checked.").asRuntimeException());
            }
        }
    }
}
