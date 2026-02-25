package az.nmsoft.clinic.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LogStore {
    private final Map<String, Deque<LogEvent>> logs = new ConcurrentHashMap<>();
    private final LogBroadcaster broadcaster;
    private final int maxLines;

    public LogStore(LogBroadcaster broadcaster,
                    @Value("${lab.logs.maxLines:500}") int maxLines) {
        this.broadcaster = broadcaster;
        this.maxLines = maxLines;
    }

    public void append(String key, String message) {
        if (key == null || key.trim().isEmpty()) key = "system";
        LogEvent event = new LogEvent(key, message, System.currentTimeMillis());
        Deque<LogEvent> deque = logs.computeIfAbsent(key, k -> new ArrayDeque<>(maxLines));
        synchronized (deque) {
            while (deque.size() >= maxLines) {
                deque.pollFirst();
            }
            deque.addLast(event);
        }
        broadcaster.publish(event);
    }

    public List<LogEvent> get(String key) {
        if (key == null || key.trim().isEmpty()) return new ArrayList<LogEvent>();
        Deque<LogEvent> deque = logs.get(key);
        if (deque == null) return new ArrayList<LogEvent>();
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void clear(String key) {
        if (key == null || key.trim().isEmpty()) return;
        Deque<LogEvent> deque = logs.get(key);
        if (deque == null) return;
        synchronized (deque) {
            deque.clear();
        }
    }
}
