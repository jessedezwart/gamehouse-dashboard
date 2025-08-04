package nl.jessedezwart.gamehouse.dto;

import java.time.Instant;

public class SessionTimelineDTO {
    public String username;
    public String game;
    public Instant start;
    public Instant end;

    public SessionTimelineDTO(String username, String game, Instant start, Instant end) {
        this.username = username;
        this.game = game;
        this.start = start;
        this.end = end;
    }
}
