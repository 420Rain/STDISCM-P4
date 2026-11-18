FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY common ./common
COPY auth-service ./auth-service
COPY course-service ./course-service
COPY enrollment-service ./enrollment-service
COPY grade-service ./grade-service
RUN mvn -B -pl grade-service -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/grade-service/target/grade-service-1.0.0.jar app.jar
EXPOSE 8084 9092
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
