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
import net.dv8tion.jda.api.events.user.UserActivityEndEvent;
import net.dv8tion.jda.api.events.user.UserActivityStartEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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

    private final Map<String, String> trackedUsers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JDA jda;

    @Autowired
    public DiscordBotService(GameSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @PostConstruct
    public void startBot() {
        try {
            // Enable presence/member intents, cache members and activities, and chunk all
            // members into cache
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES)
                    .setMemberCachePolicy(MemberCachePolicy.ALL) // Cache all members so presence updates apply
                    .enableCache(CacheFlag.ACTIVITY, CacheFlag.ONLINE_STATUS) // Cache activities and online status
                    .setChunkingFilter(ChunkingFilter.ALL) // Request all guild members from gateway at startup
                    .addEventListeners(new PresenceListener())
                    .build()
                    .awaitReady();

            for (Guild guild : jda.getGuilds()) {
                guild.loadMembers().onSuccess(members -> {
                    for (Member member : members) {
                        if (!member.getUser().isBot()) {
                            String id = member.getId();
                            String name = member.getEffectiveName();
                            trackedUsers.put(id, name);
                        }
                    }
                    log.info("Tracking {} users in guild '{}'", trackedUsers.size(), guild.getName());
                });
            }

            log.info("Discord bot connected as {}", jda.getSelfUser().getAsTag());
            log.info("Connected to {} guild(s)", jda.getGuilds().size());

            // Immediate pass: log guilds and attempt a cached read of presences
            for (Guild guild : jda.getGuilds()) {
                log.info("Inspecting guild: {} (ID: {})", guild.getName(), guild.getId());
                scanGuildOnce(guild, "startup-initial");
            }

            // Delayed rescan to allow presence payloads to arrive after chunking
            scheduler.schedule(() -> {
                try {
                    for (Guild guild : jda.getGuilds()) {
                        scanGuildOnce(guild, "startup-rescan");
                    }
                } catch (Exception ex) {
                    log.error("Startup rescan failed", ex);
                }
            }, 5, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to start Discord bot", e);
        }
    }

    // Scans tracked users in the given guild, using the cache for presence and
    // activities
    private void scanGuildOnce(Guild guild, String phase) {
        for (Map.Entry<String, String> entry : trackedUsers.entrySet()) {
            String userId = entry.getKey();
            String username = entry.getValue();

            Member member = guild.getMemberById(userId); // Use cache to access presence and activities
            if (member == null) {
                log.warn("[{}] Tracked user {} not cached yet in guild {}. Waiting for chunking/presence.", phase,
                        username, guild.getName());
                continue;
            }

            log.info("[{}] Cached activities for {}:", phase, username);
            for (Activity a : member.getActivities()) {
                log.info("[{}]   - Name: {}, Type: {}, Raw: {}", phase, a.getName(), a.getType(), a);
            }

            Activity playing = member.getActivities().stream()
                    .filter(a -> a.getType() == Activity.ActivityType.PLAYING)
                    .findFirst()
                    .orElse(null);

            if (playing != null) {
                boolean alreadyTracked = sessionRepo.findByDiscordUserIdAndActiveTrue(userId).isPresent();
                if (!alreadyTracked) {
                    GameSession session = new GameSession();
                    session.setDiscordUserId(userId);
                    session.setUsername(username);
                    session.setGame(playing.getName());
                    session.setStartTime(Instant.now());
                    session.setTotalDuration(Duration.ZERO);
                    session.setActive(true);
                    sessionRepo.save(session);
                    log.info("[{}] Started new session for {} playing {}", phase, username, playing.getName());
                } else {
                    log.info("[{}] {} already has an active session", phase, username);
                }
            } else {
                log.info("[{}] {} is not playing anything", phase, username);
            }
        }
    }

    private class PresenceListener extends ListenerAdapter {

        @Override
        public void onUserActivityStart(UserActivityStartEvent event) {
            if (event.getUser().isBot())
                return;
            if (!trackedUsers.containsKey(event.getUser().getId()))
                return;

            Activity activity = event.getNewActivity();
            String userId = event.getUser().getId();
            String username = trackedUsers.get(userId);

            log.info("ActivityStart for {} -> {} ({})", username, activity.getName(), activity.getType());

            if (activity.getType() != Activity.ActivityType.PLAYING)
                return;

            Instant now = Instant.now();
            GameSession existing = sessionRepo.findByDiscordUserIdAndActiveTrue(userId).orElse(null);
            if (existing != null) {
                Duration duration = Duration.between(existing.getStartTime(), now);
                existing.setTotalDuration(existing.getTotalDuration().plus(duration));
                existing.setActive(false);
                sessionRepo.save(existing);
                log.info("Ended previous session for {} and added {} minutes", username, duration.toMinutes());
            }

            GameSession newSession = new GameSession();
            newSession.setDiscordUserId(userId);
            newSession.setUsername(username);
            newSession.setGame(activity.getName());
            newSession.setStartTime(now);
            newSession.setTotalDuration(Duration.ZERO);
            newSession.setActive(true);
            sessionRepo.save(newSession);
            log.info("Started new session for {} playing {}", username, activity.getName());
        }

        @Override
        public void onUserActivityEnd(UserActivityEndEvent event) {
            if (event.getUser().isBot())
                return;
            if (!trackedUsers.containsKey(event.getUser().getId()))
                return;

            Activity activity = event.getOldActivity();
            String userId = event.getUser().getId();
            String username = trackedUsers.get(userId);

            log.info("ActivityEnd for {} -> {} ({})", username, activity.getName(), activity.getType());

            if (activity.getType() != Activity.ActivityType.PLAYING)
                return;

            GameSession session = sessionRepo.findByDiscordUserIdAndActiveTrue(userId).orElse(null);
            if (session == null)
                return;

            Instant now = Instant.now();
            Duration duration = Duration.between(session.getStartTime(), now);
            session.setTotalDuration(session.getTotalDuration().plus(duration));
            session.setActive(false);
            sessionRepo.save(session);
            log.info("User {} stopped playing {}. Session recorded: {} minutes", username, activity.getName(),
                    duration.toMinutes());
        }
    }
}
