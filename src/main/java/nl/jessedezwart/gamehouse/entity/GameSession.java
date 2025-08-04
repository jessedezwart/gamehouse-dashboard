package nl.jessedezwart.gamehouse.entity;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class GameSession {
    @Id
    @GeneratedValue
    private Long id;
    private String discordUserId;

    private String username;

    private String game;

    private Instant startTime;

    private Duration totalDuration;

    private boolean active;
}
