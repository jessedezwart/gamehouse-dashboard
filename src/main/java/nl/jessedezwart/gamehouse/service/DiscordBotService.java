package nl.jessedezwart.gamehouse.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import nl.jessedezwart.gamehouse.entity.GameSession;
import nl.jessedezwart.gamehouse.repository.GameSessionRepository;

@Service
public class DiscordBotService {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotService.class);

    private final GameSessionRepository sessionRepo;

    @Value("${discord.bot.token}")
    private String token;

    // Tracks non-bot users by ID to display name
    private final Map<String, String> trackedUsers = new ConcurrentHashMap<>();

    // Single scheduler for polling and periodic tasks
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JDA jda;

    @Autowired
    public DiscordBotService(GameSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @PostConstruct
    public void startBot() {
        try {
            // Build JDA with presence/member intents and full member/activity cache
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .enableCache(CacheFlag.ACTIVITY, CacheFlag.ONLINE_STATUS)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .build()
                    .awaitReady();

            log.info("Discord bot connected as {}", jda.getSelfUser().getAsTag());
            log.info("Connected to {} guild(s)", jda.getGuilds().size());

            // Initial population of tracked users
            refreshTrackedUsers("startup");

            // Initial scan after cache warmup
            scheduler.schedule(() -> {
                try {
                    for (Guild guild : jda.getGuilds()) {
                        scanGuildOnce(guild, "startup-scan");
                    }
                } catch (Exception ex) {
                    log.error("Startup scan failed", ex);
                }
            }, 5, TimeUnit.SECONDS);

            // Polling loop to detect active game changes
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    for (Guild guild : jda.getGuilds()) {
                        scanGuildOnce(guild, "poll");
                    }
                } catch (Exception e) {
                    log.error("Polling error", e);
                }
            }, 10, 10, TimeUnit.SECONDS);

            // Periodic refresh of tracked users to pick up joins/leaves
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    refreshTrackedUsers("refresh");
                } catch (Exception e) {
                    log.error("User refresh error", e);
                }
            }, 60, 60, TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Discord bot startup was interrupted", e);
        } catch (Exception e) {
            log.error("Failed to start Discord bot", e);
        }
    }

    // Loads all non-bot members into trackedUsers
    private void refreshTrackedUsers(String phase) {
        int before = trackedUsers.size();
        for (Guild guild : jda.getGuilds()) {
            guild.loadMembers().onSuccess(members -> {
                int added = 0;
                for (Member member : members) {
                    if (!member.getUser().isBot()) {
                        String id = member.getId();
                        String name = member.getEffectiveName();
                        if (trackedUsers.put(id, name) == null) {
                            added++;
                        }
                    }
                }
                log.info("[{}] Tracked {} users in guild '{}', +{} new", phase, trackedUsers.size(), guild.getName(),
                        added);
            }).onError(t -> log.warn("[{}] Failed to load members for guild {}", phase, guild.getName(), t));
        }
        log.debug("[{}] TrackedUsers size before={}, after={}", phase, before, trackedUsers.size());
    }

    // Single pass that reconciles current activities into GameSession state
    private void scanGuildOnce(Guild guild, String phase) {
        Instant now = Instant.now();
        for (Map.Entry<String, String> entry : trackedUsers.entrySet()) {
            String userId = entry.getKey();
            String username = entry.getValue();

            Member member = guild.getMemberById(userId);
            if (member == null)
                continue;

            // Set of current PLAYING game names
            var currentGames = member.getActivities().stream()
                    .filter(a -> a.getType() == Activity.ActivityType.PLAYING)
                    .map(Activity::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(java.util.stream.Collectors.toSet());

            // All active sessions for this user
            var activeSessions = sessionRepo.findAllByDiscordUserIdAndActiveTrue(userId);

            // Close sessions for games no longer present
            for (GameSession s : activeSessions) {
                if (!currentGames.contains(s.getGame())) {
                    closeActiveSession(s, now, username);
                }
            }

            // Refresh active snapshot to avoid duplicates after closures
            var stillActive = sessionRepo.findAllByDiscordUserIdAndActiveTrue(userId).stream()
                    .map(GameSession::getGame)
                    .collect(java.util.stream.Collectors.toSet());

            // Start sessions for newly detected games
            for (String game : currentGames) {
                if (!stillActive.contains(game)) {
                    startNewSession(userId, username, game, now);
                }
            }
        }
    }

    // Closes an active session and persists the accumulated duration
    private void closeActiveSession(GameSession session, Instant now, String username) {
        Duration add = Duration.between(session.getStartTime(), now);
        session.setTotalDuration(session.getTotalDuration().plus(add));
        session.setActive(false);
        sessionRepo.save(session);
        log.info("Closed session for {} on {} (+{} min)", username, session.getGame(), add.toMinutes());
    }

    // Starts a new active session for the given user and game
    private void startNewSession(String userId, String username, String game, Instant now) {
        GameSession s = new GameSession();
        s.setDiscordUserId(userId);
        s.setUsername(username);
        s.setGame(game);
        s.setStartTime(now);
        s.setTotalDuration(Duration.ZERO);
        s.setActive(true);
        sessionRepo.save(s);
        log.info("Started session for {} playing {}", username, game);
    }
}
