package nl.jessedezwart.gamehouse.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Map<Long, Integer> getPeakConcurrency(
            @RequestParam(name = "bucketSeconds", defaultValue = "60") int bucketSeconds) {

        bucketSeconds = Math.max(30, Math.min(bucketSeconds, 900));
        Instant now = Instant.now();

        // 1) collect intervals per user
        Map<String, List<Range>> byUser = new HashMap<>();
        for (GameSession s : sessionRepo.findAll()) {
            Instant a = s.getStartTime();
            Instant b = s.isActive() ? now : a.plus(s.getTotalDuration());
            if (b.isAfter(a)) {
                byUser.computeIfAbsent(s.getUsername(), k -> new ArrayList<>())
                        .add(new Range(a, b));
            }
        }

        // 2) merge per user to avoid double counting
        List<Range> mergedAll = new ArrayList<>();
        for (List<Range> list : byUser.values()) {
            list.sort(Comparator.comparing(r -> r.start));
            Instant ms = null, me = null;
            for (Range r : list) {
                if (ms == null) {
                    ms = r.start;
                    me = r.end;
                    continue;
                }
                if (!r.start.isAfter(me)) { // overlap or touch
                    if (r.end.isAfter(me))
                        me = r.end;
                } else {
                    mergedAll.add(new Range(ms, me));
                    ms = r.start;
                    me = r.end;
                }
            }
            if (ms != null)
                mergedAll.add(new Range(ms, me));
        }

        // 3) sweep with correct bucket boundaries
        NavigableMap<Instant, Integer> events = new TreeMap<>();
        for (Range r : mergedAll) {
            Instant a = floor(r.start, bucketSeconds); // inclusive
            Instant b = ceil(r.end, bucketSeconds); // exclusive
            events.merge(a, 1, Integer::sum);
            events.merge(b, -1, Integer::sum);
        }

        // 4) expand to uniform buckets
        Map<Long, Integer> out = new LinkedHashMap<>();
        if (events.isEmpty())
            return out;
        Instant t = events.firstKey();
        int running = 0;
        while (true) {
            running += events.getOrDefault(t, 0);
            out.put(t.getEpochSecond() * 1000L, Math.max(0, running));
            Instant next = t.plusSeconds(bucketSeconds);
            if (next.isAfter(Instant.now()))
                break;
            t = next;
        }
        return out;
    }

    private static Instant floor(Instant x, int s) {
        long e = x.getEpochSecond();
        return Instant.ofEpochSecond((e / s) * s);
    }

    private static Instant ceil(Instant x, int s) {
        long e = x.getEpochSecond();
        return Instant.ofEpochSecond(((e + s - 1) / s) * s);
    }

    private static class Range {
        final Instant start, end;

        Range(Instant s, Instant e) {
            this.start = s;
            this.end = e;
        }
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