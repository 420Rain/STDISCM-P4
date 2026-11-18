package com.example.enrollment.enrollment;

import com.example.enrollment.grpc.GradeRecordsGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GradeGrpcClientConfig {
    @Bean(destroyMethod="shutdownNow")
    ManagedChannel gradeChannel(@Value("${app.grade-host}") String host, @Value("${app.grade-port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
    @Bean GradeRecordsGrpc.GradeRecordsBlockingStub gradeStub(@org.springframework.beans.factory.annotation.Qualifier("gradeChannel") ManagedChannel gradeChannel) {
        return GradeRecordsGrpc.newBlockingStub(gradeChannel);
    }
}
