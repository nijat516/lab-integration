package az.nmsoft.clinic.integration;

import com.fazecast.jSerialComm.SerialPort;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ABL90 ASTM Serial client (single class, Java 8)
 *
 * ✅ 1 barkod üçün gələn BÜTÜN blokları yığır (STX...ETB/ETX ... checksum CR [LF])
 * ✅ ETB/ETX frame-ləri ACK edir (checksum+CR(+LF) udulduqdan sonra)
 * ✅ EOT gələndə TAM mesajı (seliqəli) çap edir
 * ✅ O| segmentindən sampleId (barcode) çıxarır (best-effort)
 * ✅ R| segmentlərindən nəticələri parse edir (best-effort)
 *
 * Qeyd:
 * - ABL90 çox vaxt ASTM/CLSI LIS2-A2 stilində göndərir:
 *   ENQ -> ACK
 *   (STX frameNo payload ETB/ETX checksum CR LF) ... təkrarlanır
 *   EOT
 */
public class Abl90StableSerialClientV2 {

    // ===== CONTROL BYTES (ASTM) =====
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte ETB = 0x17;
    private static final byte CR  = 0x0D;
    private static final byte LF  = 0x0A;

    // ABL90 adətən ASCII, amma bəzi field-lər UTF-8 ola bilər
    private static final Charset ASTM_CHARSET = Charset.forName("UTF-8");

    // ===== Serial config (ABL90 çox vaxt 9600/8/N/1) =====
    private final String portName;
    private final int baudRate;
    private final String deviceId;
    private final String deviceRecordId;
    private final String resultsEndpoint;
    private final LabResultsClient resultsClient;

    private SerialPort port;
    private InputStream in;
    private OutputStream out;

    private volatile boolean running = true;

    // ===== Logging options =====
    private final boolean logRawHex;
    private final boolean logRawAscii;

    // ===== Control map for readable raw logs =====
    private final Map<Byte, String> controlMap = new HashMap<Byte, String>();

    // ===== Frame/message assembler state =====
    private boolean inFrame = false;
    private final ByteArrayOutputStream frameBuf = new ByteArrayOutputStream(4096);

    // trailer (ETB/ETX-dən sonra checksum+CR(+LF)) idarəsi
    private boolean waitingTrailer = false;
    private int trailerBytesToSkip = 0;
    private boolean waitingLfAfterTrailer = false;

    // 1 transmission = çoxlu frame
    private final ByteArrayOutputStream messageBuf = new ByteArrayOutputStream(64 * 1024);

    // optional: transmission start time
    private long transmissionStartedAt = 0L;

    public Abl90StableSerialClientV2(String portName) {
        this(portName, 9600, true, true, null, null, null, null);
    }

    public Abl90StableSerialClientV2(String portName, int baudRate, boolean logRawHex, boolean logRawAscii) {
        this(portName, baudRate, logRawHex, logRawAscii, null, null, null, null);
    }

    public Abl90StableSerialClientV2(String portName,
                                    int baudRate,
                                    boolean logRawHex,
                                    boolean logRawAscii,
                                    String deviceId,
                                    String resultsEndpoint,
                                    LabResultsClient resultsClient,
                                    String deviceRecordId) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.logRawHex = logRawHex;
        this.logRawAscii = logRawAscii;
        this.deviceId = deviceId;
        this.resultsEndpoint = resultsEndpoint;
        this.resultsClient = resultsClient;
        this.deviceRecordId = deviceRecordId;
        initControlMap();
    }

    // ================= START =================
    public void start() {
        while (running) {
            try {
                connect();
                listenLoop();
            } catch (Exception e) {
                System.err.println("❌ Connection error: " + e.getMessage());
                closePort();
                sleep(3000);
            }
        }
    }

    public void stop() {
        running = false;
        closePort();
    }

    // ================= CONNECT =================
    private void connect() throws Exception {
        // Stale handle və əvvəlki uğursuz sessiyalardan qalan vəziyyəti təmizlə
        closePort();

        SerialPort[] availablePorts;
        try {
            availablePorts = SerialPort.getCommPorts();
        } catch (Throwable t) {
            throw new RuntimeException("jSerialComm init/native load error: " + t.getMessage(), t);
        }

        port = resolvePort(portName, availablePorts);
        if (port == null) {
            throw new RuntimeException(
                    "Configured port not found: " + portName + " | available=" + formatPortList(availablePorts)
            );
        }

        port.setComPortParameters(
                baudRate,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY
        );

        // NONBLOCKING oxuma (readBytes)
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_NONBLOCKING,
                0,
                0
        );

        if (!port.openPort()) {
            System.out.println("⚠ ABL90 COM open failed, trying close/reopen: " + portName);
            try {
                port.closePort();
            } catch (Exception ignored) {}

            sleep(300);

            if (!port.openPort()) {
                throw new RuntimeException(
                        "COM port açıla bilmədi (busy/open?/permission?): " + portName
                                + " | available=" + formatPortList(availablePorts)
                );
            }
        }

        in = port.getInputStream();
        out = port.getOutputStream();

        System.out.println("✅ ABL-90 CONNECTED (" + portName + "), baud=" + baudRate);
    }

    private SerialPort resolvePort(String configuredPortName, SerialPort[] ports) {
        if (configuredPortName == null) return null;
        String wanted = configuredPortName.trim();
        if (wanted.isEmpty()) return null;

        String wantedNormalized = normalizePortName(wanted);
        if (ports == null || ports.length == 0) return null;

        for (SerialPort p : ports) {
            if (p == null) continue;
            String sys = safeTrim(p.getSystemPortName());
            String desc = safeTrim(p.getDescriptivePortName());
            String desc2 = safeTrim(p.getPortDescription());

            if (wanted.equalsIgnoreCase(sys)
                    || wanted.equalsIgnoreCase(desc)
                    || wanted.equalsIgnoreCase(desc2)
                    || wantedNormalized.equalsIgnoreCase(normalizePortName(sys))
                    || wantedNormalized.equalsIgnoreCase(normalizePortName(desc))
                    || wantedNormalized.equalsIgnoreCase(normalizePortName(desc2))) {
                return p;
            }
        }

        return null;
    }

    private String normalizePortName(String s) {
        String v = safeTrim(s);
        if (v.startsWith("/dev/")) v = v.substring(5);
        if (v.startsWith("tty.")) v = v.substring(4);
        if (v.startsWith("cu.")) v = v.substring(3);
        return v;
    }

    private String formatPortList(SerialPort[] ports) {
        if (ports == null || ports.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ports.length; i++) {
            SerialPort p = ports[i];
            if (i > 0) sb.append(", ");
            if (p == null) {
                sb.append("null");
                continue;
            }
            sb.append(safeTrim(p.getSystemPortName()));
        }
        sb.append("]");
        return sb.toString();
    }

    // ================= LISTEN LOOP =================
    private void listenLoop() throws Exception {
        byte[] buffer = new byte[4096];

        while (running && port != null && port.isOpen()) {
            int read = port.readBytes(buffer, buffer.length);

            if (read > 0) {
                if (logRawHex || logRawAscii) {
                    logRaw(buffer, read);
                }
                handleBytes(buffer, read);
            } else {
                sleep(20);
            }
        }
    }

    // ================= RAW LOG =================
    private void logRaw(byte[] data, int len) {
        StringBuilder hex = new StringBuilder();
        StringBuilder ascii = new StringBuilder();

        for (int i = 0; i < len; i++) {
            byte b = data[i];

            if (logRawHex) {
                hex.append(String.format("%02X ", b));
            }

            if (logRawAscii) {
                String mapped = controlMap.get(b);
                if (mapped != null) {
                    ascii.append(mapped);
                } else if (b >= 32 && b <= 126) {
                    ascii.append((char) b);
                } else {
                    ascii.append(String.format("<0x%02X>", b));
                }
            }
        }

        System.out.println("📥 RAW DATA");
        if (logRawHex)   System.out.println("HEX   : " + hex);
        if (logRawAscii) System.out.println("ASCII : " + ascii);
        System.out.println("------------------------------------------");
    }

    // ================= MAIN BYTE HANDLER =================
    private void handleBytes(byte[] data, int len) throws Exception {
        for (int i = 0; i < len; i++) {
            byte b = data[i];

            // 1) ETB/ETX-dən sonra checksum+CR(+LF) udulur, sonra ACK verilir
            if (waitingTrailer) {
                trailerBytesToSkip--;
                if (trailerBytesToSkip <= 0) {
                    waitingTrailer = false;
                    waitingLfAfterTrailer = true;
                }
                continue;
            }

            if (waitingLfAfterTrailer) {
                waitingLfAfterTrailer = false;
                if (b == LF) {
                    sendAck();
                    continue;
                }
                sendAck();
                // fall through to process this byte normally
            }

            // 2) Frame-dən kənarda
            if (!inFrame) {
                if (b == ENQ) {
                    System.out.println("➡ ENQ → ACK (new transmission)");
                    sendAck();
                    resetTransmission();
                    transmissionStartedAt = System.currentTimeMillis();
                    continue;
                }
                if (b == STX) {
                    System.out.println("▶ STX (Frame start)");
                    inFrame = true;
                    frameBuf.reset();
                    continue;
                }
                if (b == EOT) {
                    System.out.println("⛔ EOT (Transmission end) → print full message");
                    onTransmissionEnd();
                    continue;
                }
                if (b == NAK) {
                    System.out.println("⚠ NAK received");
                    continue;
                }
                // digər byte-lar (noise) ignore
                continue;
            }

            // 3) Frame-in içində
            if (b == ETB) {
                System.out.println("↔ ETB (More blocks) - frame end");
                finalizeFrame(false);

                // checksum(2) + CR(1) + bəzən LF(1)
                waitingTrailer = true;
                trailerBytesToSkip = 3; // checksum(2) + CR(1); LF optional

                inFrame = false;
                continue;
            }

            if (b == ETX) {
                System.out.println("◀ ETX (Last frame end)");
                finalizeFrame(true);

                waitingTrailer = true;
                trailerBytesToSkip = 3; // checksum(2) + CR(1); LF optional

                inFrame = false;
                continue;
            }

            // Data yığılır (CR payload daxilində ola bilər)
            frameBuf.write(b);
        }
    }

    /**
     * ETB/ETX görəndə frameBuf-də payload var:
     *   [frameNo][payload...]
     *
     * Biz:
     * - frameNo (0-7) varsa çıxarırıq
     * - payload olduğu kimi messageBuf-a əlavə edirik
     */
    private void finalizeFrame(boolean isLast) {
        byte[] raw = frameBuf.toByteArray();
        String s = new String(raw, ASTM_CHARSET);

        s = stripLeadingFrameNumber(s);

        // Frame payload-ını messageBuf-a əlavə edirik
        try {
            messageBuf.write(s.getBytes(ASTM_CHARSET));
        } catch (Exception ignored) {}

        // payload CR ilə bitmirsə, sonda CR qoyaq (ASTM segment ayırıcı)
        if (!s.endsWith("\r")) {
            try { messageBuf.write(CR); } catch (Exception ignored) {}
        }

        // isLast burada saxlanıla bilər (istəsən)
        if (isLast) {
            // nothing mandatory here; EOT gələndə tam mesajı çıxarırıq
        }
    }

    private String stripLeadingFrameNumber(String s) {
        if (s == null || s.isEmpty()) return "";
        char c = s.charAt(0);
        if (c >= '0' && c <= '7') {
            return s.substring(1);
        }
        return s;
    }

    // ================= Transmission end =================
    private void onTransmissionEnd() {
        String full = new String(messageBuf.toByteArray(), ASTM_CHARSET);

        if (full.trim().isEmpty()) {
            System.out.println("ℹ️ Empty transmission (no frames)");
            resetTransmission();
            return;
        }

        // Seliqəli çıxart
        String sampleId = extractSampleId(full);
        String patientId = extractPatientId(full);
        List<AstmResult> results = extractResults(full);

        long durMs = (transmissionStartedAt > 0) ? (System.currentTimeMillis() - transmissionStartedAt) : -1;

        System.out.println("✅ FULL MESSAGE READY"
                + (sampleId.isEmpty() ? "" : (" | sampleId=" + sampleId))
                + (durMs >= 0 ? (" | duration=" + durMs + "ms") : ""));
        System.out.println("========= ASTM FULL REPORT START =========");
        System.out.println(prettyAstm(full));
        System.out.println("========== ASTM FULL REPORT END ==========");

        if (!results.isEmpty()) {
            System.out.println("========= PARSED RESULTS =========");
            for (AstmResult r : results) {
                System.out.println(" - " + r.code
                        + " = " + r.value
                        + (r.unit.isEmpty() ? "" : (" " + r.unit))
                        + (r.flag.isEmpty() ? "" : (" [" + r.flag + "]")));
            }
            System.out.println("================================");
        } else {
            System.out.println("ℹ️ No R| results parsed (maybe different layout)");
        }

        // send JSON to server (if endpoint configured)
        if (resultsClient != null && resultsEndpoint != null && !resultsEndpoint.trim().isEmpty()) {
            String json = buildUnifiedResultsJson(sampleId, patientId, results, full);
            int code = resultsClient.postJson(resultsEndpoint, json);
            System.out.println("🌐 HTTP POST -> " + code);
        }

        resetTransmission();
    }

    private static String prettyAstm(String s) {
        // ASTM segmentləri CR ilə ayrılır
        return s.replace("\r", "\r\n");
    }

    private void resetTransmission() {
        messageBuf.reset();
        frameBuf.reset();
        inFrame = false;
        waitingTrailer = false;
        trailerBytesToSkip = 0;
        waitingLfAfterTrailer = false;
        transmissionStartedAt = 0L;
    }

    // ================= ACK =================
    private void sendAck() throws Exception {
        out.write(ACK);
        out.flush();
    }

    // ================= Parsing helpers =================

    /**
     * SampleId (barcode) çıxarma:
     * - Çox cihazda O| segmentində olur.
     * - 2 əsas variant:
     *   1) O|...|<barcode>...
     *   2) O|...|Sample #^2583  (ABL90 bəzən belə verir)
     */
    private String extractSampleId(String full) {
        String[] lines = full.split("\\r");
        for (String line : lines) {
            if (!line.startsWith("O|")) continue;

            String[] f = line.split("\\|", -1);

            // variant A: "Sample #^2583"
            for (int i = 0; i < f.length; i++) {
                String val = safeTrim(f[i]);
                if (val.startsWith("Sample #^")) {
                    String[] p = val.split("\\^", -1);
                    if (p.length > 1 && !safeTrim(p[1]).isEmpty()) {
                        return safeTrim(p[1]);
                    }
                }
            }

            // variant B: klassik - O-3 və ya O-4
            String o3 = (f.length > 2) ? safeTrim(f[2]) : "";
            String o4 = (f.length > 3) ? safeTrim(f[3]) : "";

            // o4 "0^50009^009^^S1^SC" kimi ola bilər -> ^ ilə bölüb barcode çıxarmağa çalışaq
            String fromO4 = extractSampleFromO4(o4);
            if (!fromO4.isEmpty()) return fromO4;

            if (!o3.isEmpty()) return o3;
            if (!o4.isEmpty()) return o4;
        }
        return "";
    }

    /**
     * PatientId çıxarma:
     * ASTM-də çox vaxt P| segmentində olur.
     * Nümunə: P|1||3412||^||||...
     */
    private String extractPatientId(String full) {
        String[] lines = full.split("\\r");
        for (String line : lines) {
            if (!line.startsWith("P|")) continue;
            String[] f = line.split("\\|", -1);
            String p2 = (f.length > 2) ? safeTrim(f[2]) : "";
            String p3 = (f.length > 3) ? safeTrim(f[3]) : "";
            String id = firstNonEmpty(p3, p2, "");
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    private String extractSampleFromO4(String o4) {
        if (o4 == null) return "";
        String t = o4.trim();
        if (t.isEmpty()) return "";

        String[] parts = t.split("\\^", -1);

        // bəzi cihazlarda barcode parts[1] və ya parts[2] olur
        String p1 = (parts.length > 1) ? safeTrim(parts[1]) : "";
        String p2 = (parts.length > 2) ? safeTrim(parts[2]) : "";

        // bəzən 0^50009^009 -> 50009 və ya 009 lazım olur
        return firstNonEmpty(p1, p2, "");
    }

    /**
     * R| segment parse (best-effort):
     * Nümunə:
     *   R|1|^^^pH^M|7.460|||H||F|||20251214091822
     *
     * Tipik:
     *   R-2: test id (^^^pH^M)
     *   R-3: value
     *   R-4: unit (mmHg, mmol/L, ...)
     *   R-6 və ya R-7: flag (H/L/N vs)
     */
    private List<AstmResult> extractResults(String full) {
        List<AstmResult> out = new ArrayList<AstmResult>();
        String[] lines = full.split("\\r");

        for (String line : lines) {
            if (!line.startsWith("R|")) continue;

            String[] f = line.split("\\|", -1);

            String testField = (f.length > 2) ? safeTrim(f[2]) : "";
            String value     = (f.length > 3) ? safeTrim(f[3]) : "";
            String unit      = (f.length > 4) ? safeTrim(f[4]) : "";

            // flag-lar müxtəlif yerdə ola bilər, ən çox 6-8 arası rast gəlir
            String flag = "";
            for (int idx : new int[]{6, 7, 5, 8}) {
                if (f.length > idx) {
                    String cand = safeTrim(f[idx]);
                    if (isFlagLike(cand)) { flag = cand; break; }
                }
            }

            String code = parseTestCodeFromR2(testField);

            if (!code.isEmpty() && !value.isEmpty()) {
                AstmResult r = new AstmResult();
                r.code = code;
                r.value = value;
                r.unit = unit == null ? "" : unit;
                r.flag = flag;
                out.add(r);
            }
        }

        return out;
    }

    private boolean isFlagLike(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        // ABL90-da H/L/N, A, F, P kimi də ola bilər; çox sərt etməyək
        return t.length() <= 3;
    }

    private String parseTestCodeFromR2(String r2) {
        // r2: "^^^pH^M" -> pH
        if (r2 == null) return "";
        String[] p = r2.split("\\^", -1);
        // "^^^pH^M" -> p[3] = "pH"
        if (p.length > 3 && !safeTrim(p[3]).isEmpty()) return safeTrim(p[3]);

        // fallback: içində "^^^" varsa sonra gələn hissəni götür
        int idx = r2.indexOf("^^^");
        if (idx >= 0) {
            String after = r2.substring(idx + 3);
            int hat = after.indexOf('^');
            return (hat >= 0) ? safeTrim(after.substring(0, hat)) : safeTrim(after);
        }
        return safeTrim(r2);
    }

    // ================= Small DTO =================
    private static final class AstmResult {
        String code = "";
        String value = "";
        String unit = "";
        String flag = "";
    }

    // ================= UTILS =================
    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        if (c != null && !c.trim().isEmpty()) return c.trim();
        return "";
    }

    private String buildUnifiedResultsJson(String sampleId, String patientId, List<AstmResult> results, String rawAstm) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"deviceId\":").append(q(deviceId)).append(",");
        sb.append("\"deviceRecordId\":").append(q(deviceRecordId)).append(",");
        sb.append("\"deviceType\":").append(q("ABL90")).append(",");
        sb.append("\"patientId\":").append(q(patientId)).append(",");
        sb.append("\"sampleId\":").append(q(sampleId)).append(",");
        sb.append("\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            AstmResult r = results.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append("\"code\":").append(q(r.code)).append(",")
                    .append("\"value\":").append(q(r.value)).append(",")
                    .append("\"unit\":").append(q(r.unit)).append(",")
                    .append("\"flag\":").append(q(r.flag))
                    .append("}");
        }
        sb.append("],");
        sb.append("\"raw\":").append(q(rawAstm));
        sb.append("}");
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) s = "";
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private void closePort() {
        try {
            if (port != null && port.isOpen()) {
                port.closePort();
            }
        } catch (Exception ignored) {}
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void initControlMap() {
        controlMap.put(ENQ, "<ENQ>");
        controlMap.put(ACK, "<ACK>");
        controlMap.put(NAK, "<NAK>");
        controlMap.put(EOT, "<EOT>");
        controlMap.put(STX, "<STX>");
        controlMap.put(ETX, "<ETX>");
        controlMap.put(ETB, "<ETB>");
        controlMap.put(CR,  "<CR>");
        controlMap.put(LF,  "<LF>");
    }

    // ================= MAIN =================
    public static void main(String[] args) {
        String port = (args.length > 0) ? args[0] : "COM3";
        int baud = (args.length > 1) ? Integer.parseInt(args[1]) : 9600;

        Abl90StableSerialClientV2 client =
                new Abl90StableSerialClientV2(port, baud, true, true);

        client.start();
    }

    // ================= OPTIONAL: test with sample string =================
    // İstəsən bu helper ilə "<STX>..<CR><ETB>.." tipli log string-i normal ASTM text-ə çevirə bilərsən.
    // Bu serial stream deyil, sadəcə debug üçündür.
    public static String normalizeDebugMessage(String debug) {
        if (debug == null) return "";
        return debug
                .replace("<STX>", "")
                .replace("<ETB>", "")
                .replace("<ETX>", "")
                .replace("<EOT>", "")
                .replace("<CR>", "\r")
                .replace("<LF>", "\n");
    }
}
