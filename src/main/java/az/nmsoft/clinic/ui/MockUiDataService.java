package az.nmsoft.clinic.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class MockUiDataService {
    private final DeviceRegistry deviceRegistry;
    private final LogStore logStore;

    @Value("${lab.ui.mock.deviceCount:3}")
    private int deviceCount;

    @Value("${lab.ui.mock.intervalMs:1000}")
    private long intervalMs;

    private volatile boolean started = false;

    public MockUiDataService(DeviceRegistry deviceRegistry, LogStore logStore) {
        this.deviceRegistry = deviceRegistry;
        this.logStore = logStore;
    }

    public synchronized void startMockDevices() {
        if (started) return;
        started = true;

        List<String> types = Arrays.asList("C311", "ABL90", "DH76");
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
        Random random = new Random();

        for (int i = 0; i < deviceCount; i++) {
            String type = types.get(i % types.size());
            DeviceInfo info = new DeviceInfo();
            info.name = type;
            info.type = type;
            info.deviceId = "MOCK-" + type + "-" + (i + 1);
            info.portName = "COM-MOCK-" + (i + 1);
            info.listenPort = 5200 + i;
            info.baudRate = 9600;
            info.dataBits = 8;
            info.httpEndpoint = "http://mock.local/api/results";

            String key = deviceRegistry.register(info);
            logStore.append(key, "Mock device registered: " + info.deviceId);

            scheduler.scheduleAtFixedRate(
                    () -> emitMockLog(key, type, random),
                    500,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void emitMockLog(String key, String type, Random random) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        String msg;
        if ("C311".equals(type)) {
            msg = ts + " [C311] FRAME OK, sample=3" + random.nextInt(100) + ", test=570";
        } else if ("ABL90".equals(type)) {
            msg = ts + " [ABL90] RESULT pH=" + (7.20 + (random.nextDouble() * 0.30));
        } else {
            msg = ts + " [DH76] ORU^R01 posted, code=" + (200 + random.nextInt(50));
        }
        logStore.append(key, msg);
    }
}
