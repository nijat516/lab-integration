package az.nmsoft.clinic.integration;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CobasC311AstSerialServer {

    /* ===== ASTM CONTROL ===== */
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte ETB = 0x17;
    private static final byte CR  = 0x0D;
    private static final byte LF  = 0x0A;

    /* ===== SERIAL ===== */
    private final String portName;
    private final String deviceId;
    private final String deviceRecordId;
    private final String resultsEndpoint;
    private SerialPort port;
    private InputStream in;
    private OutputStream out;

    /* ===== MESSAGE STATE ===== */
    private final StringBuilder messageBuffer = new StringBuilder();
    private final CobasC311OrderClient orderClient;
    private final LabResultsClient resultsClient;
    private volatile boolean running = true;

    public CobasC311AstSerialServer(String portName) {
        this(portName, null, null, null, null, null);
    }

    public CobasC311AstSerialServer(String portName,
                                    String deviceId,
                                    CobasC311OrderClient orderClient,
                                    String resultsEndpoint,
                                    LabResultsClient resultsClient,
                                    String deviceRecordId) {
        this.portName = portName;
        this.deviceId = deviceId;
        this.orderClient = orderClient;
        this.resultsEndpoint = resultsEndpoint;
        this.resultsClient = resultsClient;
        this.deviceRecordId = deviceRecordId;
    }

    /* ===== START ===== */
    public void start() {
        running = true;
        while (running) {
            try {
                if (port == null || !port.isOpen()) {
                    connect();
                }
                listen(); // normally never returns while port is open
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log("❌ ERROR: " + e.getMessage());
                safeClose();
                sleep(1500);
            }
        }
        safeClose();
    }

    public void stop() {
        running = false;
        safeClose();
        log("🛑 C311 stop requested");
    }

    /* ===== CONNECT ===== */
    private void connect() throws Exception {
        // Stale handle qalarsa əvvəlcə təmizlə.
        safeClose();

        port = SerialPort.getCommPort(portName);
        port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);

        // ✅ Semi-blocking: idle vaxtı exception atmır, CPU spin etmir
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!port.openPort()) {
            log("⚠ COM open failed, trying close/reopen: " + portName);
            try {
                port.closePort();
            } catch (Exception ignored) {}

            sleep(300);

            if (!port.openPort()) {
                throw new RuntimeException("COM port açıla bilmədi (busy/open?): " + portName);
            }
        }

        in = new PushbackInputStream(port.getInputStream(), 1);
        out = port.getOutputStream();

        messageBuffer.setLength(0);

        log("✅ CONNECTED: " + portName);
    }

    /* ===== LISTEN ===== */
    private void listen() throws Exception {
        while (running && port != null && port.isOpen()) {
            try {
                int b = in.read();

                if (b < 0) {
                    // normal idle
                    continue;
                }

                handleByte((byte) b);

            } catch (SerialPortTimeoutException e) {
                // ✅ timeout normaldır — heç nə etmə
            }
        }
    }

    /* ===== BYTE HANDLER ===== */
    private void handleByte(byte b) throws Exception {

        if (b == ENQ) {
            log("[CTRL][IN] <ENQ>");
            messageBuffer.setLength(0);
            sendACK();
            return;
        }

        if (b == STX) {
            Frame f = readFrame();

            if (!f.validChecksum) {
                log("❌ BAD CHECKSUM (term=" + (f.terminator == ETX ? "ETX" : "ETB") + ") → NAK");
                sendNAK();
                return;
            }

            // checksum ok → ACK
            sendACK();

            // append text (frame payload text only)
            messageBuffer.append(f.text);

            log("📦 FRAME OK (" + (f.terminator == ETX ? "ETX" : "ETB") + "), len=" + f.text.length());
            return;
        }

        if (b == EOT) {
            log("[CTRL][IN] <EOT>");

            String fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0);

            if (fullMessage.trim().isEmpty()) {
                log("⚠ Empty ASTM message (EOT received but buffer empty)");
                return;
            }

            log("📥 ASTM MESSAGE (IN):\n" + visualize(fullMessage));

            if (fullMessage.contains("TSREQ^REAL")) {
                // 1) TSREQ parse
                TsreqInfo tsreq = TsreqParser.parse(fullMessage);

                // 2) OrderData build (static defaults)
                OrderData od = new OrderData();
                od.sendingApp = "COZUM"; // logdakı kimi
                od.patientId = "11111111"; // demo; realda server/JSON
                od.sampleId = tsreq.sampleId;
                od.rackId = tsreq.rackId;
                od.position = tsreq.position;
                od.sampleType = (tsreq.sampleType != null ? tsreq.sampleType : "S1");
                od.containerType = (tsreq.containerType != null ? tsreq.containerType : "SC");
                od.testCodes = Arrays.asList("570", "571", "678"); // demo; realda server/JSON
                od.priority = "R";
                od.orderDateTime = nowYYYYMMDDHHMMSS(); // demo; realda server/JSON
                od.comment = "NICATTEST GASIMOVTEST^NMSOFT POLIKLINIKASI 1^^^"; // demo; realda server/JSON

                // 3) Optional: fetch from API and override defaults if provided
                if (orderClient != null) {
                    CobasC311OrderResponse resp = orderClient.fetchOrder(new CobasC311OrderRequest(
                            tsreq.sampleId,
                            deviceId
                    ));
                    applyOrderResponse(od, resp);
                }

                String reply = buildTsdwnReply(od);

                // 4) Reply send (turnaround: EOT-dan sonra host başlayır)
                sendMessage(reply);
            }

            if (fullMessage.contains("RSUPL^REAL") || fullMessage.contains("RSUPL^BATCH")) {
                C311Results results = C311ResultsParser.parse(fullMessage);
                if (resultsClient != null && resultsEndpoint != null && !resultsEndpoint.trim().isEmpty()) {
                    String json = buildUnifiedResultsJson(results);
                    int code = resultsClient.postJson(resultsEndpoint, json);
                    log("🌐 HTTP POST -> " + code);
                }
            }
        }
    }

    /* ===== FRAME READER (reads after STX already consumed) ===== */
    private Frame readFrame() throws Exception {

        int fnByte = in.read(); // ASCII '1'..'7'
        if (fnByte < 0) throw new RuntimeException("Unexpected EOF while reading frame number");

        List<Byte> data = new ArrayList<>();
        int b;
        byte terminator;

        while (true) {
            b = in.read();
            if (b < 0) throw new RuntimeException("Unexpected EOF while reading frame payload");

            if (b == ETX || b == ETB) {
                terminator = (byte) b;
                break;
            }

            data.add((byte) b);
        }

        int cs1 = in.read();
        int cs2 = in.read();
        if (cs1 < 0 || cs2 < 0) throw new RuntimeException("Unexpected EOF while reading checksum");

        // CR is expected, LF is optional
        int cr = in.read();
        if (cr == CR) {
            int lf = in.read();
            if (lf >= 0 && lf != LF) {
                ((PushbackInputStream) in).unread(lf);
            }
        } else if (cr >= 0) {
            ((PushbackInputStream) in).unread(cr);
        }

        byte[] textBytes = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) textBytes[i] = data.get(i);

        int expected = Integer.parseInt("" + (char) cs1 + (char) cs2, 16);
        int actual = checksum(fnByte, textBytes, terminator);

        String text = new String(textBytes, "US-ASCII");
        return new Frame(text, terminator, expected == actual);
    }

    /* ===== SEND MESSAGE (ETB / ETX, ENQ/ACK, per-frame ACK/NAK) ===== */
    private void sendMessage(String text) throws Exception {

        log("📤 ASTM MESSAGE (OUT):\n" + visualize(text));

        byte[] payload = text.getBytes("US-ASCII");
        int offset = 0;
        int frameNo = 1;
        final int MAX = 240;

        // 1) Host initiates: ENQ -> ACK
        out.write(ENQ);
        out.flush();

        int first = waitForAckOrNak(5000);
        if (first != ACK) {
            throw new RuntimeException("No ACK after ENQ (resp=" + first + ")");
        }

        // 2) frames
        while (offset < payload.length) {

            int len = Math.min(MAX, payload.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, offset, chunk, 0, len);

            boolean last = (offset + len) >= payload.length;
            byte term = last ? ETX : ETB;
            byte fn = (byte) ('0' + frameNo);

            int cs = checksum(fn, chunk, term);
            byte[] csHex = String.format("%02X", cs).getBytes("US-ASCII");

            int attempts = 0;
            while (true) {
                attempts++;

                out.write(STX);
                out.write(fn);
                out.write(chunk);
                out.write(term);
                out.write(csHex);
                out.write(CR);
                out.write(LF);
                out.flush();

                int resp = waitForAckOrNak(5000);
                if (resp == ACK) {
                    break; // next frame
                }
                if (resp == NAK) {
                    if (attempts >= 3) {
                        throw new RuntimeException("Frame NAK after retries (fn=" + (char) fn + ")");
                    }
                    log("⚠ Frame NAK, retrying fn=" + (char) fn + " attempt=" + attempts);
                    continue;
                }

                throw new RuntimeException("Unexpected response after frame: " + resp);
            }

            offset += len;
            frameNo++;
            if (frameNo > 7) frameNo = 1;
        }

        // 3) EOT
        out.write(EOT);
        out.flush();

        log("✅ SEND COMPLETE (EOT)");
    }

    private int waitForAckOrNak(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < end) {
            int b = in.read();
            if (b < 0) continue;
            if (b == ACK || b == NAK) return b;
            // ignore other bytes (some devices send noise/control)
        }
        throw new RuntimeException("ACK/NAK timeout");
    }

    /* ===== CHECKSUM (SUM MOD 256) ===== */
    private int checksum(int fn, byte[] text, byte term) {
        int sum = fn & 0xFF;           // ASCII frame number included
        for (byte b : text) sum += (b & 0xFF);
        sum += (term & 0xFF);
        return sum & 0xFF;
    }

    /* ===== IO ===== */
    private void sendACK() throws Exception {
        out.write(ACK);
        out.flush();
        log("[CTRL][OUT] <ACK>");
    }

    private void sendNAK() throws Exception {
        out.write(NAK);
        out.flush();
        log("[CTRL][OUT] <NAK>");
    }

    /* ===== BUILDER (log format compatible) ===== */
    public static String buildTsdwnReply(OrderData d) {
        StringBuilder sb = new StringBuilder();

        // H|\^&|||COZUM^||||||TSDWN^REPLY
        sb.append("H|\\^&|||")
                .append(nullToEmpty(d.sendingApp)).append("^")
                .append("||||||")
                .append("TSDWN^REPLY")
                .append("\r");

        // P|1||179754|||||U
        sb.append("P|1||")
                .append(nullToEmpty(d.patientId))
                .append("|||||U")
                .append("\r");

        // O|1|3057765|0^50001^1^^S1^SC|^^^690^\^^^256^|R||20251206094400||||A||||1||||||||||O
        sb.append("O|1|")
                .append(nullToEmpty(d.sampleId))
                .append("|0^")
                .append(nullToEmpty(d.rackId)).append("^")
                .append(nullToEmpty(d.position))
                .append("^^")
                .append(nullToEmpty(d.sampleType)).append("^")
                .append(nullToEmpty(d.containerType))
                .append("|")
                .append(buildTestList(d.testCodes))
                .append("|")
                .append(nullToEmpty(d.priority))
                .append("||")
                .append(nullToEmpty(d.orderDateTime))
                .append("||||A||||1||||||||||O")
                .append("\r");

        // C|1|L|...|G (optional)
        if (d.comment != null && !d.comment.trim().isEmpty()) {
            sb.append("C|1|L|")
                    .append(d.comment)
                    .append("|G")
                    .append("\r");
        }

        sb.append("L|1|N\r");
        return sb.toString();
    }

    private static String buildTestList(List<String> testCodes) {
        if (testCodes == null || testCodes.isEmpty()) return "";
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < testCodes.size(); i++) {
            if (i > 0) t.append("\\");
            t.append("^^^").append(testCodes.get(i)).append("^");
        }
        return t.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String buildUnifiedResultsJson(C311Results results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"deviceId\":").append(q(deviceId)).append(",");
        sb.append("\"deviceRecordId\":").append(q(deviceRecordId)).append(",");
        sb.append("\"deviceType\":").append(q("C311")).append(",");
        sb.append("\"patientId\":").append(q(results.patientId)).append(",");
        sb.append("\"sampleId\":").append(q(results.sampleId)).append(",");
        sb.append("\"results\":[");
        for (int i = 0; i < results.items.size(); i++) {
            C311ResultItem r = results.items.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append("\"code\":").append(q(r.code).replaceAll("/", "")).append(",")
                    .append("\"value\":").append(q(r.value)).append(",")
                    .append("\"unit\":").append(q(r.unit)).append(",")
                    .append("\"flag\":").append(q(r.flag))
                    .append("}");
        }
        sb.append("],");
        sb.append("\"raw\":").append(q(results.raw));
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

    private static void applyOrderResponse(OrderData od, CobasC311OrderResponse resp) {
        if (resp == null) return;
        if (!isBlank(resp.sendingApp)) od.sendingApp = resp.sendingApp;
        if (!isBlank(resp.patientId)) od.patientId = resp.patientId;
        if (!isBlank(resp.sampleId)) od.sampleId = resp.sampleId;
        if (!isBlank(resp.rackId)) od.rackId = resp.rackId;
        if (!isBlank(resp.position)) od.position = resp.position;
        if (!isBlank(resp.sampleType)) od.sampleType = resp.sampleType;
        if (!isBlank(resp.containerType)) od.containerType = resp.containerType;
        if (resp.testCodes != null && !resp.testCodes.isEmpty()) od.testCodes = resp.testCodes;
        if (!isBlank(resp.priority)) od.priority = resp.priority;
        if (!isBlank(resp.orderDateTime)) od.orderDateTime = resp.orderDateTime;
        if (!isBlank(resp.comment)) od.comment = resp.comment;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (!isBlank(v)) {
                return v.trim();
            }
        }
        return "";
    }

    private static final class C311Results {
        String sampleId = "";
        String patientId = "";
        String raw = "";
        List<C311ResultItem> items = new ArrayList<C311ResultItem>();
    }

    private static final class C311ResultItem {
        String code = "";
        String value = "";
        String unit = "";
        String flag = "";
    }

    private static final class C311ResultsParser {
        static C311Results parse(String msg) {
            C311Results out = new C311Results();
            out.raw = msg;
            String[] recs = msg.split("\r");
            for (String r : recs) {
                if (r.startsWith("P|")) {
                    String[] f = r.split("\\|", -1);
                    String p2 = (f.length > 2) ? safeTrim(f[2]) : "";
                    String p3 = (f.length > 3) ? safeTrim(f[3]) : "";
                    out.patientId = firstNonEmpty(p2, p3, out.patientId);
                } else if (r.startsWith("O|")) {
                    String[] f = r.split("\\|", -1);
                    if (f.length > 2 && !isBlank(f[2])) {
                        out.sampleId = f[2].trim();
                    }
                } else if (r.startsWith("R|")) {
                    String[] f = r.split("\\|", -1);
                    C311ResultItem item = new C311ResultItem();
                    // R|1|^^^570^|123|mg/dL|...|H
                    item.code = (f.length > 2) ? extractCode(f[2]) : "";
                    item.value = (f.length > 3) ? safeTrim(f[3]) : "";
                    item.unit = (f.length > 4) ? safeTrim(f[4]) : "";
                    item.flag = (f.length > 7) ? safeTrim(f[7]) : "";
                    if (!isBlank(item.code) && !isBlank(item.value)) {
                        out.items.add(item);
                    }
                }
            }
            return out;
        }

        private static String extractCode(String r2) {
            if (r2 == null) return "";
            String[] p = r2.split("\\^", -1);
            for (int i = p.length - 1; i >= 0; i--) {
                if (!isBlank(p[i])) return p[i].trim();
            }
            return r2.trim();
        }

        private static String safeTrim(String s) {
            return s == null ? "" : s.trim();
        }
    }

    /* ===== TSREQ PARSER (minimal, from your logs) ===== */
    private static final class TsreqParser {
        static TsreqInfo parse(String msg) {
            // TSREQ message example:
            // H|...|TSREQ^REAL|...
            // Q|1|^^               3057765^0^50001^001^^S0^||ALL|...
            // We parse sampleId, rack, pos, sampleType/containerType if present.

            TsreqInfo info = new TsreqInfo();

            String[] recs = msg.split("\r");
            for (String r : recs) {
                if (r.startsWith("Q|")) {
                    // fields separated by |
                    String[] f = r.split("\\|", -1);
                    if (f.length >= 3) {
                        // f[2] contains: "^^               3057765^0^50001^001^^S0^"
                        String q = f[2];
                        // remove possible leading ^^
                        while (q.startsWith("^")) q = q.substring(1);

                        String[] parts = q.split("\\^", -1);

                        // By observation from log:
                        // parts[0] = (spaces + sampleId)
                        // parts[1] = "0"
                        // parts[2] = rackId
                        // parts[3] = "001" (position)
                        // later contains sample info like S0 etc.
                        if (parts.length > 0) info.sampleId = parts[0].trim();
                        if (parts.length > 2) info.rackId = parts[2].trim();
                        if (parts.length > 3) info.position = parts[3].trim();

                        // sample type may appear later like ... ^^S0^  (not always)
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i] != null && parts[i].startsWith("S")) {
                                info.sampleType = parts[i].trim(); // e.g. S0
                                break;
                            }
                        }
                    }
                }
            }

            // defaults
            if (info.sampleType == null || info.sampleType.isEmpty()) info.sampleType = "S1";
            info.containerType = "SC";
            return info;
        }
    }

    private static final class TsreqInfo {
        String sampleId;
        String rackId;
        String position;
        String sampleType;
        String containerType;
    }

    /* ===== ORDER DATA ===== */
    public static final class OrderData {
        public String sendingApp;    // COZUM
        public String patientId;     // 179754
        public String sampleId;      // 3057765
        public String rackId;        // 50001
        public String position;      // 1
        public String sampleType;    // S1
        public String containerType; // SC
        public List<String> testCodes; // 690,256
        public String priority;      // R
        public String orderDateTime; // YYYYMMDDHHMMSS
        public String comment;       // patient name etc.
    }

    /* ===== UTILS ===== */
    private void safeClose() {
        try { if (port != null) port.closePort(); } catch (Exception ignored) {}
        port = null;
        in = null;
        out = null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void log(String s) {
        System.out.println(s);
    }

    private String visualize(String s) {
        // record-level view
        return s.replace("\r", "\\r\n");
    }

    private static String nowYYYYMMDDHHMMSS() {
        java.time.LocalDateTime dt = java.time.LocalDateTime.now();
        return String.format("%04d%02d%02d%02d%02d%02d",
                dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
    }

    /* ===== FRAME DTO ===== */
    private static final class Frame {
        final String text;
        final byte terminator;
        final boolean validChecksum;

        Frame(String text, byte terminator, boolean validChecksum) {
            this.text = text;
            this.terminator = terminator;
            this.validChecksum = validChecksum;
        }
    }

    /* ===== MAIN ===== */
    public static void main(String[] args) {
        new CobasC311AstSerialServer("COM3").start();
    }
}
