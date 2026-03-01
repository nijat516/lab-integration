package az.nmsoft.clinic.ui;

import az.nmsoft.clinic.bootstrap.LabDeviceBootstrap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ui")
public class LogController {
    private final DeviceRegistry deviceRegistry;
    private final LogStore logStore;
    private final LogBroadcaster broadcaster;
    private final LabDeviceBootstrap labDeviceBootstrap;

    public LogController(DeviceRegistry deviceRegistry, LogStore logStore, LogBroadcaster broadcaster, LabDeviceBootstrap labDeviceBootstrap) {
        this.deviceRegistry = deviceRegistry;
        this.logStore = logStore;
        this.broadcaster = broadcaster;
        this.labDeviceBootstrap = labDeviceBootstrap;
    }

    @GetMapping("/devices")
    public List<DeviceInfo> devices() {
        return deviceRegistry.list();
    }

    @GetMapping("/logs/{key}")
    public List<LogEvent> logs(@PathVariable("key") String key) {
        return logStore.get(key);
    }

    @PostMapping("/logs/{key}/clear")
    public void clearLogs(@PathVariable("key") String key) {
        logStore.clear(key);
    }

    @PostMapping("/devices/{key}/restart")
    public Map<String, Object> restartDevice(@PathVariable("key") String key) {
        boolean ok = labDeviceBootstrap.restartDevice(key);
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("ok", ok);
        out.put("key", key);
        return out;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.addEmitter();
    }

    @GetMapping(value = "/stream/{key}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamByKey(@PathVariable("key") String key) {
        return broadcaster.addEmitter(key);
    }
}
