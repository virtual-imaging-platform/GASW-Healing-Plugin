package fr.insalyon.creatis.gasw.plugin.listener.healing.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Service
public class CommandStateRegistry {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final HealingService healingService;
    private final ConcurrentMap<String, CommandState> states;

    public CommandStateRegistry(HealingService healingService) {
        this.healingService = healingService;
        states = new ConcurrentHashMap<>();
    }

    public CommandState getOrCreate(String command) {
        return states.computeIfAbsent(command, CommandState::new);
    }

    @Scheduled(fixedDelayString = "${healing.sleep-time}", timeUnit = TimeUnit.SECONDS)
    public void run() {
        for (CommandState state : states.values()) {
            try {
                if (state.shouldKillAll()) {
                    healingService.killAllJobs(state.getCommand());
                } else if (state.hasEnoughData()) {
                    healingService.replicateJobs(state);
                }
            } catch (Exception ex) {
                logger.error("Error: ", ex);
            }
        }
    }
}