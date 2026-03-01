package az.nmsoft.clinic.bootstrap;

import az.nmsoft.clinic.integration.CobasC311AstSerialServer;
import az.nmsoft.clinic.integration.Dh76MllpGateway;
import az.nmsoft.clinic.integration.Abl90StableSerialClientV2;
import az.nmsoft.clinic.integration.CobasC311OrderClient;
import az.nmsoft.clinic.integration.LabResultsClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import az.nmsoft.clinic.ui.DeviceInfo;
import az.nmsoft.clinic.ui.DeviceRegistry;
import az.nmsoft.clinic.ui.MockUiDataService;

@Component
public class LabDeviceBootstrap implements CommandLineRunner {

    @Value("${lab.devices.url:https://clinic.nmtech.az/nm-clinic-api/api/v0/lab/devices}")
    private String devicesUrl;

    @Value("${lab.devices.retryDelayMs:5000}")
    private long retryDelayMs;

    @Value("${lab.devices.dh76.resultsUrl:}")
    private String defaultDh76ResultsUrl;

    @Value("${lab.devices.abl90.resultsUrl:}")
    private String defaultAbl90ResultsUrl;

    @Value("${lab.devices.resultsUrl:}")
    private String defaultResultsUrl;

    @Value("${lab.ui.mock:false}")
    private boolean mockMode;

    @Value("${lab.devices.mockOnError:true}")
    private boolean mockOnError;

    private final WebClient webClient = WebClient.builder().build();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final DeviceRegistry deviceRegistry;
    private final MockUiDataService mockUiDataService;
    private final CobasC311OrderClient cobasC311OrderClient;
    private final LabResultsClient labResultsClient;
    private final Map<String, Runnable> restartHooks = new ConcurrentHashMap<String, Runnable>();

    public LabDeviceBootstrap(DeviceRegistry deviceRegistry,
                              MockUiDataService mockUiDataService,
                              CobasC311OrderClient cobasC311OrderClient,
                              LabResultsClient labResultsClient) {
        this.deviceRegistry = deviceRegistry;
        this.mockUiDataService = mockUiDataService;
        this.cobasC311OrderClient = cobasC311OrderClient;
        this.labResultsClient = labResultsClient;
    }

    @Override
    public void run(String... args) {
        if (mockMode) {
            log("🧪 UI mock mode enabled; skipping device bootstrap");
            return;
        }
        startDevicesFromServer();
    }

    private void startDevicesFromServer() {
        while (true) {
            try {
                List<DeviceConfig> devices = fetchDevices(devicesUrl);
                if (devices.isEmpty()) {
                    log("⚠ No devices returned from server, retrying...");
                    sleep(retryDelayMs);
                    continue;
                }

                log("✅ Devices fetched: " + devices.size());
                for (DeviceConfig d : devices) {
                    try {
                        startDevice(d);
                    } catch (Exception ex) {
                        log("❌ Device init error: " + ex.getMessage() + " | " + describeDevice(d));
                    }
                }
                return;
            } catch (Exception e) {
                e.printStackTrace();
                log("❌ Device fetch error: " + e.getMessage());
                if (mockOnError) {
                    log("🧪 Falling back to mock device list");
                    mockUiDataService.startMockDevices();
                    return;
                }
                sleep(retryDelayMs);
            }
        }
    }

    private List<DeviceConfig> fetchDevices(String url) throws Exception {
        List<DeviceConfig> list = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<DeviceConfig>>() {})
                .block();

        if (list == null) {
            return new ArrayList<DeviceConfig>();
        }

        List<DeviceConfig> out = new ArrayList<>();
        for (DeviceConfig cfg : list) {
            if (cfg == null) {
                log("⚠ Skipping null device entry");
                continue;
            }
            if (!cfg.enabled) {
                log("⏭ Skipping disabled device: " + cfg.safeLabel());
                continue;
            }
            out.add(cfg);
        }
        return out;
    }

    private void startDevice(DeviceConfig d) {
        if (d == null) {
            log("⚠ Device is null, skipping");
            return;
        }

        String type = normalizeType(d.name);
        if (type.isEmpty()) {
            log("⚠ Unknown device type, skipping: " + d.safeLabel() + " | " + describeDevice(d));
            return;
        }

        switch (type) {
            case "C311":
                if (isBlank(d.portName)) {
                    log("⚠ Missing portName for C311, skipping: " + d.safeLabel() + " | " + describeDevice(d));
                    return;
                }
                String c311DeviceId = isBlank(d.deviceId) ? d.id : d.deviceId;
                String c311Key = deviceRegistry.register(toDeviceInfo(d, type));
                String c311Endpoint = firstNonEmpty(d.httpEndpoint, firstNonEmpty(defaultResultsUrl, ""));
                log("🚀 Starting C311: key=" + c311Key + ", deviceId=" + safe(c311DeviceId) + ", port=" + safe(d.portName));
                executor.submit(() -> runWithRestart(c311Key, () -> {
                    CobasC311AstSerialServer server = new CobasC311AstSerialServer(
                            d.portName, c311DeviceId, cobasC311OrderClient, c311Endpoint, labResultsClient, d.id
                    );
                    registerRestartHook(c311Key, server::stop);
                    try {
                        server.start();
                    } finally {
                        clearRestartHook(c311Key);
                    }
                }));
                return;
            case "ABL90":
                if (isBlank(d.portName)) {
                    log("⚠ Missing portName for ABL90, skipping: " + d.safeLabel() + " | " + describeDevice(d));
                    return;
                }
                int baud = d.baudRate > 0 ? d.baudRate : 9600;
                String ablEndpoint = firstNonEmpty(d.httpEndpoint, firstNonEmpty(defaultResultsUrl, defaultAbl90ResultsUrl));
                String abl90Key = deviceRegistry.register(toDeviceInfo(d, type));
                executor.submit(() -> runWithRestart(abl90Key, () -> {
                    Abl90StableSerialClientV2 client = new Abl90StableSerialClientV2(
                            d.portName, baud, true, true, d.deviceId, ablEndpoint, labResultsClient, d.id
                    );
                    registerRestartHook(abl90Key, client::stop);
                    try {
                        client.start();
                    } finally {
                        clearRestartHook(abl90Key);
                    }
                }));
                return;
            case "DH76":
                if (d.listenPort <= 0) {
                    log("⚠ Missing listenPort for DH76, skipping: " + d.safeLabel() + " | " + describeDevice(d));
                    return;
                }
                String endpoint = firstNonEmpty(d.httpEndpoint, firstNonEmpty(defaultResultsUrl, defaultDh76ResultsUrl));
                if (endpoint.isEmpty()) {
                    log("⚠ Missing httpEndpoint for DH76, skipping: " + d.safeLabel() + " | " + describeDevice(d));
                    return;
                }
                String deviceId = isBlank(d.deviceId) ? d.id : d.deviceId;
                String dh76Key = deviceRegistry.register(toDeviceInfo(d, type));
                executor.submit(() -> runWithRestart(dh76Key, () -> {
                    Dh76MllpGateway gateway = new Dh76MllpGateway(d.listenPort, endpoint, deviceId, labResultsClient, d.id);
                    registerRestartHook(dh76Key, gateway::stop);
                    try {
                        gateway.start();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        clearRestartHook(dh76Key);
                    }
                }));
                return;
            default:
                log("⚠ Unknown device type, skipping: " + d.safeLabel());
                return;
        }
    }

    private void runWithRestart(String name, Runnable task) {
        Thread.currentThread().setName(name);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                task.run();
            } catch (Exception e) {
                log("❌ " + name + " stopped: " + e.getMessage());
                sleep(3000);
            }
        }
    }

    public boolean restartDevice(String key) {
        if (isBlank(key)) return false;
        Runnable stopAction = restartHooks.get(key);
        if (stopAction == null) {
            log("⚠ Restart requested for inactive/unknown device: " + key);
            return false;
        }
        try {
            log("🔄 Restart requested: " + key);
            stopAction.run();
            return true;
        } catch (Exception e) {
            log("❌ Restart failed for " + key + ": " + e.getMessage());
            return false;
        }
    }

    private void registerRestartHook(String key, Runnable stopAction) {
        if (isBlank(key) || stopAction == null) return;
        restartHooks.put(key, stopAction);
    }

    private void clearRestartHook(String key) {
        if (isBlank(key)) return;
        restartHooks.remove(key);
    }

    private static String normalizeType(String raw) {
        if (raw == null) return "";
        String t = raw.toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (t.contains("C311") || t.contains("COBAS")) return "C311";
        if (t.contains("ABL90") || t.contains("ABL")) return "ABL90";
        if (t.contains("DH76") || t.contains("DH")) return "DH76";
        return "";
    }

    private static DeviceInfo toDeviceInfo(DeviceConfig d, String type) {
        DeviceInfo info = new DeviceInfo();
        info.name = d.name;
        info.deviceId = d.deviceId;
        info.portName = d.portName;
        info.baudRate = d.baudRate;
        info.dataBits = d.dataBits;
        info.listenPort = d.listenPort;
        info.httpEndpoint = d.httpEndpoint;
        info.type = type;
        return info;
    }

    private static void log(String s) {
        System.out.println(s);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return "";
    }

    private static String describeDevice(DeviceConfig d) {
        if (d == null) return "device=null";
        return "id=" + safe(d.id)
                + ", deviceId=" + safe(d.deviceId)
                + ", name=" + safe(d.name)
                + ", portName=" + safe(d.portName)
                + ", listenPort=" + d.listenPort
                + ", httpEndpoint=" + safe(d.httpEndpoint);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class DeviceConfig {
        @JsonProperty("id")
        public String id;

        @JsonProperty("deviceId")
        public String deviceId;

        @JsonProperty("name")
        public String name;

        @JsonProperty("portName")
        public String portName;

        @JsonProperty("baudRate")
        public int baudRate;

        @JsonProperty("dataBits")
        public int dataBits;

        @JsonProperty("listenPort")
        public int listenPort;

        @JsonProperty("httpEndpoint")
        public String httpEndpoint;

        @JsonProperty("enabled")
        public boolean enabled = true;

        String safeLabel() {
            String base = !isBlank(deviceId) ? deviceId : id;
            if (isBlank(base)) base = name;
            return base == null ? "" : base;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
