package com.example.enrollment.grade;

import com.example.enrollment.grpc.CourseDirectoryGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CourseGrpcClientConfig {
    @Bean(destroyMethod="shutdownNow") ManagedChannel courseChannel(@Value("${app.course-host}") String host, @Value("${app.course-port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    }
    @Bean CourseDirectoryGrpc.CourseDirectoryBlockingStub courseStub(@org.springframework.beans.factory.annotation.Qualifier("courseChannel") ManagedChannel courseChannel) {
        return CourseDirectoryGrpc.newBlockingStub(courseChannel);
    }
}
