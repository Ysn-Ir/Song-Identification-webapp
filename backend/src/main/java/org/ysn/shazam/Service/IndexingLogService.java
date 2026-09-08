package org.ysn.shazam.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class IndexingLogService {

    private static final Logger log = LoggerFactory.getLogger(IndexingLogService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 minute timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("log")
                    .data(Map.of(
                            "time", LocalTime.now().format(TIME_FMT),
                            "level", "SYSTEM",
                            "message", "Acoustic Indexing Log Terminal connected."
                    )));
        } catch (IOException ignored) {}

        return emitter;
    }

    public void log(String level, String message) {
        String timestamp = LocalTime.now().format(TIME_FMT);
        log.info("[INDEXER-LOG] [{}] {}", level, message);

        Map<String, String> payload = Map.of(
                "time", timestamp,
                "level", level,
                "message", message
        );

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("log").data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
