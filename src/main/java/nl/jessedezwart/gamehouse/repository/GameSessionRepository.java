package nl.jessedezwart.gamehouse.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import nl.jessedezwart.gamehouse.entity.GameSession;

public interface GameSessionRepository extends JpaRepository<GameSession, Long> {
    List<GameSession> findByActiveTrue();
    Optional<GameSession> findByDiscordUserIdAndActiveTrue(String userId);
}
