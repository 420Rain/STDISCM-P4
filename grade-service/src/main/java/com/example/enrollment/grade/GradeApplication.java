package com.example.enrollment.grade;

import com.example.enrollment.common.ApiExceptionHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class GradeApplication {
    public static void main(String[] args) { SpringApplication.run(GradeApplication.class, args); }
    @Bean CommandLineRunner initializeGradeDatabase(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS grades (id BIGSERIAL PRIMARY KEY, student_id BIGINT NOT NULL, course_id BIGINT NOT NULL, course_code VARCHAR(30) NOT NULL DEFAULT '', section_id BIGINT NOT NULL, final_grade NUMERIC(5,2) NOT NULL CHECK(final_grade >= 0 AND final_grade <= 100), updated_by BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(student_id,section_id))");
            jdbc.execute("ALTER TABLE grades ADD COLUMN IF NOT EXISTS course_code VARCHAR(30) NOT NULL DEFAULT ''");
            jdbc.update("UPDATE grades SET course_code='CS101' WHERE course_id=1001 AND course_code=''");
            jdbc.update("UPDATE grades SET course_code='MATH201' WHERE course_id=1002 AND course_code=''");
            jdbc.update("INSERT INTO grades(student_id,course_id,course_code,section_id,final_grade,updated_by) VALUES (1,1001,'CS101',101,92.50,2) ON CONFLICT(student_id,section_id) DO NOTHING");
        };
    }
}
