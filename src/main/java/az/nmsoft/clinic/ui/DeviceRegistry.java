package az.nmsoft.clinic.ui;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DeviceRegistry {
    private final List<DeviceInfo> devices = new CopyOnWriteArrayList<>();
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    public String register(DeviceInfo info) {
        String base = buildKey(info);
        String key = base;
        int i = 2;
        while (!keys.add(key)) {
            key = base + "-" + i;
            i++;
        }
        info.key = key;
        devices.add(info);
        return key;
    }

    public List<DeviceInfo> list() {
        return new ArrayList<>(devices);
    }

    public String resolveKey(String threadName) {
        if (threadName == null || threadName.trim().isEmpty()) return "system";
        for (String key : keys) {
            if (threadName.equals(key) || threadName.startsWith(key + "-")) {
                return key;
            }
        }
        return threadName;
    }

    private static String buildKey(DeviceInfo info) {
        String id = firstNonEmpty(info.portName, toString(info.listenPort), info.deviceId, info.name);
        String type = info.type == null ? "" : info.type;
        String prefix = normalize(type.isEmpty() ? info.name : type);
        if (id.isEmpty()) return prefix;
        return prefix + "-" + id;
    }

    private static String normalize(String s) {
        if (s == null) return "device";
        String t = s.trim().toLowerCase();
        t = t.replaceAll("\\s+", "-");
        return t.isEmpty() ? "device" : t;
    }

    private static String toString(int v) {
        return v > 0 ? String.valueOf(v) : "";
    }

    private static String firstNonEmpty(String a, String b, String c, String d) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        if (c != null && !c.trim().isEmpty()) return c.trim();
        if (d != null && !d.trim().isEmpty()) return d.trim();
        return "";
    }
}
