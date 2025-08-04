package nl.jessedezwart.gamehouse.controller;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import nl.jessedezwart.gamehouse.dto.SessionTimelineDTO;
import nl.jessedezwart.gamehouse.entity.GameSession;
import nl.jessedezwart.gamehouse.repository.GameSessionRepository;

@Controller
public class DashboardController {

    private final GameSessionRepository sessionRepo;

    public DashboardController(GameSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/games/table")
    public String getCurrentSessions(Model model) {
        List<GameSession> sessions = sessionRepo.findByActiveTrue();
        Instant now = Instant.now();

        List<Map<String, String>> formatted = sessions.stream().map(session -> {
            Duration total = session.getTotalDuration()
                    .plus(Duration.between(session.getStartTime(), now));

            Map<String, String> entry = new HashMap<>();
            entry.put("username", session.getUsername());
            entry.put("game", session.getGame());
            entry.put("duration", formatDuration(total));
            return entry;
        }).collect(Collectors.toList());

        model.addAttribute("sessions", formatted);
        return "fragments/gameTable";
    }

    @GetMapping("/games/leaderboard")
    public String getLeaderboard(Model model) {
        List<GameSession> allSessions = sessionRepo.findAll();
        Instant now = Instant.now();

        Map<String, Duration> totals = new HashMap<>();

        for (GameSession session : allSessions) {
            Duration d = session.getTotalDuration();
            if (session.isActive()) {
                d = d.plus(Duration.between(session.getStartTime(), now));
            }
            totals.merge(session.getGame(), d, Duration::plus);
        }

        List<Map<String, String>> leaderboard = totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> Map.of(
                        "game", e.getKey(),
                        "duration", formatDuration(e.getValue())))
                .toList();

        model.addAttribute("leaderboard", leaderboard);
        return "fragments/leaderboard";
    }

    private String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutes() % 60;
        long s = d.getSeconds() % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    @GetMapping("/games/stats/game-distribution")
    @ResponseBody
    public Map<String, Long> getGamePlaytimeDistribution() {
        List<GameSession> allSessions = sessionRepo.findAll();
        Instant now = Instant.now();

        Map<String, Duration> totals = new HashMap<>();
        for (GameSession session : allSessions) {
            Duration d = session.getTotalDuration();
            if (session.isActive()) {
                d = d.plus(Duration.between(session.getStartTime(), now));
            }
            totals.merge(session.getGame(), d, Duration::plus);
        }

        return totals.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toMinutes()));
    }

    @GetMapping("/games/stats/user-leaderboard")
    public String getUserLeaderboard(Model model) {
        List<GameSession> sessions = sessionRepo.findAll();
        Instant now = Instant.now();

        Map<String, Duration> totals = new HashMap<>();
        for (GameSession session : sessions) {
            Duration d = session.getTotalDuration();
            if (session.isActive()) {
                d = d.plus(Duration.between(session.getStartTime(), now));
            }
            totals.merge(session.getUsername(), d, Duration::plus);
        }

        List<Map<String, String>> leaderboard = totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> Map.of(
                        "username", e.getKey(),
                        "duration", formatDuration(e.getValue())))
                .toList();

        model.addAttribute("leaderboard", leaderboard);
        return "fragments/userLeaderboard";
    }

    @GetMapping("/games/stats/peak-concurrency")
    @ResponseBody
    public Map<String, Integer> getPeakConcurrency() {
        List<GameSession> sessions = sessionRepo.findAll();
        Instant now = Instant.now();

        TreeMap<Instant, Integer> timeline = new TreeMap<>();

        for (GameSession session : sessions) {
            Instant start = session.getStartTime();
            Instant end = session.isActive() ? now : start.plus(session.getTotalDuration());

            while (start.isBefore(end)) {
                Instant rounded = start.truncatedTo(ChronoUnit.MINUTES).plusSeconds(-(start.getEpochSecond() % 300)); // 5-minute
                                                                                                                      // bucket
                timeline.merge(rounded, 1, Integer::sum);
                start = start.plusSeconds(300);
            }
        }

        return timeline.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        Map.Entry::getValue,
                        (a, b) -> b,
                        LinkedHashMap::new));
    }

    @GetMapping("/games/stats/session-timeline")
    @ResponseBody
    public List<SessionTimelineDTO> getSessionTimeline() {
        List<GameSession> sessions = sessionRepo.findAll();
        Instant now = Instant.now();

        return sessions.stream()
                .filter(s -> s.getStartTime() != null)
                .map(s -> {
                    Instant end = s.isActive() ? now : s.getStartTime().plus(s.getTotalDuration());
                    return new SessionTimelineDTO(
                            s.getUsername(),
                            s.getGame(),
                            s.getStartTime(),
                            end);
                })
                .collect(Collectors.toList());
    }

}