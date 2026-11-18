package com.example.enrollment.course;

import com.example.enrollment.common.TokenPrincipal;
import com.example.enrollment.common.TokenSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private static final Logger log = LoggerFactory.getLogger(CourseController.class);
    private final JdbcTemplate jdbc; private final String jwtSecret;
    public CourseController(JdbcTemplate jdbc, @Value("${app.jwt-secret}") String jwtSecret) { this.jdbc = jdbc; this.jwtSecret = jwtSecret; }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        TokenSupport.require(request, jwtSecret);
        log.info("[DB SELECT] course catalog and section availability");
        List<Map<String, Object>> courses = jdbc.query("SELECT id,code,title,description FROM courses ORDER BY code",
                (rs, row) -> new LinkedHashMap<>(Map.of("id", rs.getLong("id"), "code", rs.getString("code"), "title", rs.getString("title"), "description", Objects.toString(rs.getString("description"), ""))));
        for (Map<String,Object> course : courses) {
            List<Map<String,Object>> sections = jdbc.query("""
                    SELECT s.id,s.section_name,s.faculty_user_id,s.faculty_name,s.meeting_days,s.start_time,s.end_time,
                           s.enrollment_open,s.capacity,
                           (SELECT count(*) FROM enrollment_reservations r WHERE r.section_id=s.id
                              AND (r.status='CONFIRMED' OR (r.status='PENDING' AND r.expires_at > now()))) AS occupied
                    FROM sections s WHERE s.course_id=? ORDER BY s.section_name
                    """, (rs,row) -> {
                int occupied = rs.getInt("occupied");
                int capacity = rs.getInt("capacity");
                return Map.ofEntries(
                        Map.entry("id",rs.getLong("id")), Map.entry("sectionName",rs.getString("section_name")),
                        Map.entry("facultyUserId",rs.getLong("faculty_user_id")), Map.entry("facultyName",rs.getString("faculty_name")),
                        Map.entry("meetingDays",rs.getString("meeting_days")), Map.entry("startTime",rs.getString("start_time")),
                        Map.entry("endTime",rs.getString("end_time")), Map.entry("enrollmentOpen",rs.getBoolean("enrollment_open")),
                        Map.entry("capacity",capacity), Map.entry("occupied",occupied), Map.entry("slotsLeft",Math.max(0,capacity-occupied)));
            }, course.get("id"));
            course.put("sections", sections);
        }
        return courses;
    }

    @GetMapping("/my-sections")
    public List<Map<String,Object>> mySections(HttpServletRequest request) {
        TokenPrincipal principal = TokenSupport.requireRole(request, jwtSecret, "FACULTY");
        log.info("[DB SELECT] sections assigned to facultyId={}", principal.userId());
        return jdbc.query("""
                SELECT s.id section_id,s.section_name,s.faculty_name,s.meeting_days,s.start_time,s.end_time,
                       s.capacity,s.enrollment_open,c.id course_id,c.code,c.title,
                       (SELECT count(*) FROM enrollment_reservations r WHERE r.section_id=s.id
                         AND (r.status='CONFIRMED' OR (r.status='PENDING' AND r.expires_at > now()))) occupied
                FROM sections s JOIN courses c ON c.id=s.course_id
                WHERE s.faculty_user_id=? ORDER BY c.code,s.section_name
                """, (rs,row) -> {
            int capacity=rs.getInt("capacity"), occupied=rs.getInt("occupied");
            return Map.ofEntries(
                    Map.entry("sectionId",rs.getLong("section_id")), Map.entry("sectionName",rs.getString("section_name")),
                    Map.entry("facultyName",rs.getString("faculty_name")), Map.entry("meetingDays",rs.getString("meeting_days")),
                    Map.entry("startTime",rs.getString("start_time")), Map.entry("endTime",rs.getString("end_time")),
                    Map.entry("capacity",capacity), Map.entry("slotsLeft",Math.max(0,capacity-occupied)),
                    Map.entry("courseId",rs.getLong("course_id")), Map.entry("courseCode",rs.getString("code")),
                    Map.entry("courseTitle",rs.getString("title")), Map.entry("enrollmentOpen",rs.getBoolean("enrollment_open")));
        }, principal.userId());
    }
}
