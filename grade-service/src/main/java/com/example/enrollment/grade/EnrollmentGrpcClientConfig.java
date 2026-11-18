package com.example.enrollment.grade;

import com.example.enrollment.grpc.EnrollmentDirectoryGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnrollmentGrpcClientConfig {
    @Bean(destroyMethod="shutdownNow")
    ManagedChannel enrollmentChannel(@Value("${app.enrollment-host}") String host, @Value("${app.enrollment-port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
    @Bean EnrollmentDirectoryGrpc.EnrollmentDirectoryBlockingStub enrollmentStub(@org.springframework.beans.factory.annotation.Qualifier("enrollmentChannel") ManagedChannel enrollmentChannel) {
        return EnrollmentDirectoryGrpc.newBlockingStub(enrollmentChannel);
    }
}
