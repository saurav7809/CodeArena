package com.codearena.career;

import com.codearena.problem.ProblemService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class CareerService {
    public record Skill(String id, String title, String description, List<String> problems) {}
    public record Milestone(String title, String description, List<Skill> skills) {}
    public record Track(String id, String title, String description, List<Milestone> milestones) {}
    public record Roadmaps(List<Track> tracks, Set<String> completedSkills, List<ProblemService.PracticeLink> problems) {}
    public record Plan(String text, Instant generatedAt) {}

    private final JdbcClient jdbc;
    private final ProblemService problems;
    private final List<Track> tracks;
    private final Set<String> skillIds;
    private final Set<String> problemSlugs;

    CareerService(JdbcClient jdbc, ProblemService problems, ObjectMapper json,
                  @Value("classpath:career/roadmaps.json") Resource catalog) throws IOException {
        this.jdbc = jdbc;
        this.problems = problems;
        try (var input = catalog.getInputStream()) {
            tracks = List.copyOf(json.readValue(input, new TypeReference<List<Track>>() {}));
        }
        var skills = tracks.stream().flatMap(track -> track.milestones().stream())
            .flatMap(milestone -> milestone.skills().stream()).toList();
        skillIds = skills.stream().map(Skill::id).collect(Collectors.toUnmodifiableSet());
        problemSlugs = skills.stream().flatMap(skill -> skill.problems().stream()).collect(Collectors.toUnmodifiableSet());
    }

    Roadmaps roadmaps(long userId) {
        return new Roadmaps(tracks, completedSkills(userId), problems.practiceLinks(problemSlugs, userId));
    }

    public Track track(String id) {
        return tracks.stream().filter(track -> track.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Roadmap not found."));
    }

    public Set<String> completedSkills(long userId) {
        if (userId == 0) return Set.of();
        return jdbc.sql("SELECT skill_id FROM user_skills WHERE user_id = :user")
            .param("user", userId).query(String.class).set();
    }

    void updateSkill(long userId, String skillId, boolean completed) {
        if (!skillIds.contains(skillId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found.");
        String sql = completed ? """
            INSERT INTO user_skills (user_id, skill_id) VALUES (:user, :skill)
            ON CONFLICT (user_id, skill_id) DO NOTHING
            """ : "DELETE FROM user_skills WHERE user_id = :user AND skill_id = :skill";
        jdbc.sql(sql).param("user", userId).param("skill", skillId).update();
    }

    Plan plan(long userId, String trackId) {
        track(trackId);
        return jdbc.sql("SELECT plan_text, generated_at FROM career_plans WHERE user_id = :user AND track_id = :track")
            .param("user", userId).param("track", trackId)
            .query((rs, row) -> new Plan(rs.getString("plan_text"), rs.getTimestamp("generated_at").toInstant()))
            .optional().orElseGet(() -> new Plan(null, null));
    }

    public void savePlan(long userId, String trackId, String text) {
        jdbc.sql("""
            INSERT INTO career_plans (user_id, track_id, plan_text) VALUES (:user, :track, :text)
            ON CONFLICT (user_id, track_id) DO UPDATE SET plan_text = excluded.plan_text, generated_at = now()
            """).param("user", userId).param("track", trackId).param("text", text.replace('\0', '\uFFFD')).update();
    }

    void deletePlan(long userId, String trackId) {
        track(trackId);
        jdbc.sql("DELETE FROM career_plans WHERE user_id = :user AND track_id = :track")
            .param("user", userId).param("track", trackId).update();
    }
}
