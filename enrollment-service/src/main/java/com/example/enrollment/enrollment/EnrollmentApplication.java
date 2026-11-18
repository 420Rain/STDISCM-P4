package com.example.enrollment.enrollment;

import com.example.enrollment.common.ApiExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@Import(ApiExceptionHandler.class)
public class EnrollmentApplication {
    public static void main(String[] args) { SpringApplication.run(EnrollmentApplication.class, args); }

    @Bean(name="shard0DataSource", destroyMethod="close")
    @ConfigurationProperties(prefix="app.shards.shard0")
    HikariDataSource shard0DataSource() { return new HikariDataSource(); }

    @Bean(name="shard1DataSource", destroyMethod="close")
    @ConfigurationProperties(prefix="app.shards.shard1")
    HikariDataSource shard1DataSource() { return new HikariDataSource(); }

    @Bean(name="shard0Jdbc") JdbcTemplate shard0Jdbc(@org.springframework.beans.factory.annotation.Qualifier("shard0DataSource") HikariDataSource ds) { return new JdbcTemplate(ds); }
    @Bean(name="shard1Jdbc") JdbcTemplate shard1Jdbc(@org.springframework.beans.factory.annotation.Qualifier("shard1DataSource") HikariDataSource ds) { return new JdbcTemplate(ds); }
    @Bean(name="shard0Tx") TransactionTemplate shard0Tx(@org.springframework.beans.factory.annotation.Qualifier("shard0DataSource") HikariDataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }
    @Bean(name="shard1Tx") TransactionTemplate shard1Tx(@org.springframework.beans.factory.annotation.Qualifier("shard1DataSource") HikariDataSource ds) { return new TransactionTemplate(new DataSourceTransactionManager(ds)); }

    @Bean CommandLineRunner initializeShards(
            @org.springframework.beans.factory.annotation.Qualifier("shard0Jdbc") JdbcTemplate shard0,
            @org.springframework.beans.factory.annotation.Qualifier("shard1Jdbc") JdbcTemplate shard1) {
        return args -> {
            for (JdbcTemplate jdbc : new JdbcTemplate[]{shard0, shard1}) {
                jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS enrollments (
                      id BIGSERIAL PRIMARY KEY,
                      student_id BIGINT NOT NULL,
                      course_id BIGINT NOT NULL,
                      section_id BIGINT NOT NULL,
                      course_code VARCHAR(30) NOT NULL DEFAULT '',
                      section_name VARCHAR(40) NOT NULL DEFAULT '',
                      faculty_name VARCHAR(160) NOT NULL DEFAULT 'Faculty',
                      meeting_days VARCHAR(80) NOT NULL DEFAULT '',
                      start_time VARCHAR(5) NOT NULL DEFAULT '',
                      end_time VARCHAR(5) NOT NULL DEFAULT '',
                      reservation_id UUID NOT NULL,
                      status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                      enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      CONSTRAINT uq_student_course UNIQUE(student_id,course_id)
                    )
                    """);
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS course_code VARCHAR(30) NOT NULL DEFAULT ''");
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS section_name VARCHAR(40) NOT NULL DEFAULT ''");
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS faculty_name VARCHAR(160) NOT NULL DEFAULT 'Faculty'");
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS meeting_days VARCHAR(80) NOT NULL DEFAULT ''");
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS start_time VARCHAR(5) NOT NULL DEFAULT ''");
                jdbc.execute("ALTER TABLE enrollments ADD COLUMN IF NOT EXISTS end_time VARCHAR(5) NOT NULL DEFAULT ''");
                jdbc.execute("CREATE TABLE IF NOT EXISTS enrollment_student_locks (student_id BIGINT PRIMARY KEY)");
                // Backfill sample/demo metadata for rows created by the first version of this project.
                jdbc.update("UPDATE enrollments SET course_code='CS101',section_name=CASE section_id WHEN 101 THEN 'S1' WHEN 102 THEN 'S2' ELSE section_name END,faculty_name=CASE section_id WHEN 101 THEN 'Alex Morgan' WHEN 102 THEN 'Jamie Reyes' ELSE faculty_name END,meeting_days=CASE section_id WHEN 101 THEN 'Monday | Thursday' WHEN 102 THEN 'Tuesday | Friday' ELSE meeting_days END,start_time=CASE section_id WHEN 101 THEN '12:45' WHEN 102 THEN '09:00' ELSE start_time END,end_time=CASE section_id WHEN 101 THEN '14:15' WHEN 102 THEN '10:30' ELSE end_time END WHERE course_id=1001 AND course_code=''");
                jdbc.update("UPDATE enrollments SET course_code='MATH201',section_name='S1',faculty_name='Taylor Santos',meeting_days='Monday | Wednesday',start_time='14:30',end_time='16:00' WHERE course_id=1002 AND course_code=''");
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id)");
            }
        };
    }
}
