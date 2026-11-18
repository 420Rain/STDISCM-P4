package com.example.enrollment.course;

import com.example.enrollment.common.ApiExceptionHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class CourseApplication {
    public static void main(String[] args) { SpringApplication.run(CourseApplication.class, args); }

    @Bean CommandLineRunner initializeCourseDatabase(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS courses (id BIGINT PRIMARY KEY, code VARCHAR(30) NOT NULL UNIQUE, title VARCHAR(180) NOT NULL, description TEXT)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS sections (id BIGINT PRIMARY KEY, course_id BIGINT NOT NULL REFERENCES courses(id), section_name VARCHAR(40) NOT NULL, faculty_user_id BIGINT NOT NULL, faculty_name VARCHAR(160) NOT NULL DEFAULT 'Faculty', meeting_days VARCHAR(80) NOT NULL DEFAULT 'Monday | Wednesday', start_time VARCHAR(5) NOT NULL DEFAULT '12:45', end_time VARCHAR(5) NOT NULL DEFAULT '14:15', enrollment_open BOOLEAN NOT NULL DEFAULT true, capacity INTEGER NOT NULL DEFAULT 30, UNIQUE(course_id, section_name))");
            jdbc.execute("ALTER TABLE sections ADD COLUMN IF NOT EXISTS faculty_name VARCHAR(160) NOT NULL DEFAULT 'Faculty'");
            jdbc.execute("ALTER TABLE sections ADD COLUMN IF NOT EXISTS meeting_days VARCHAR(80) NOT NULL DEFAULT 'Monday | Wednesday'");
            jdbc.execute("ALTER TABLE sections ADD COLUMN IF NOT EXISTS start_time VARCHAR(5) NOT NULL DEFAULT '12:45'");
            jdbc.execute("ALTER TABLE sections ADD COLUMN IF NOT EXISTS end_time VARCHAR(5) NOT NULL DEFAULT '14:15'");
            jdbc.execute("CREATE TABLE IF NOT EXISTS enrollment_reservations (request_id UUID PRIMARY KEY, student_id BIGINT NOT NULL, course_id BIGINT NOT NULL, section_id BIGINT NOT NULL REFERENCES sections(id), status VARCHAR(20) NOT NULL, expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            jdbc.update("INSERT INTO courses(id,code,title,description) VALUES (1001,'CS101','Introduction to Computing','Foundations of computing and programming.'),(1002,'MATH201','Discrete Mathematics','Logic, sets, relations, and graphs.') ON CONFLICT (id) DO NOTHING");
            // Exactly one assigned faculty member per section; a course may have several sections.
            jdbc.update("INSERT INTO sections(id,course_id,section_name,faculty_user_id,faculty_name,meeting_days,start_time,end_time,enrollment_open,capacity) VALUES (101,1001,'S1',2,'Alex Morgan','Monday | Thursday','12:45','14:15',true,30),(102,1001,'S2',3,'Jamie Reyes','Tuesday | Friday','09:00','10:30',true,30),(201,1002,'S1',4,'Taylor Santos','Monday | Wednesday','14:30','16:00',true,25) ON CONFLICT (id) DO NOTHING");
            // Update existing demo volumes from the original A/B labels to S1/S2.
            jdbc.update("UPDATE sections SET section_name='S1', faculty_name='Alex Morgan', meeting_days='Monday | Thursday', start_time='12:45', end_time='14:15' WHERE id=101");
            jdbc.update("UPDATE sections SET section_name='S2', faculty_name='Jamie Reyes', meeting_days='Tuesday | Friday', start_time='09:00', end_time='10:30' WHERE id=102");
            jdbc.update("UPDATE sections SET section_name='S1', faculty_name='Taylor Santos', meeting_days='Monday | Wednesday', start_time='14:30', end_time='16:00' WHERE id=201");
            jdbc.update("UPDATE courses SET description='Foundations of computing and programming.' WHERE id=1001 AND description IS NULL");
        };
    }
}
