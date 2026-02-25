package az.nmsoft.clinic.ui;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LogBroadcaster {
    private final Map<SseEmitter, String> emitters = new ConcurrentHashMap<>();

    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(emitter, "");
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    public SseEmitter addEmitter(String key) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(emitter, key);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    public void publish(LogEvent event) {
        for (Map.Entry<SseEmitter, String> entry : emitters.entrySet()) {
            try {
                String key = entry.getValue();
                if (key == null || key.isEmpty() || key.equals(event.key)) {
                    entry.getKey().send(SseEmitter.event().name("log").data(event));
                }
            } catch (IOException e) {
                emitters.remove(entry.getKey());
            }
        }
    }
}
