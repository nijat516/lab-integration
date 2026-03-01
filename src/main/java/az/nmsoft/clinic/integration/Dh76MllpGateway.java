package az.nmsoft.clinic.integration;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * DH-76 (və ümumən HL7 MLLP cihazları) üçün TCP/IP gateway.
 *
 * - TCP server açır (cihaz adapterə qoşulur)
 * - MLLP ilə HL7 mesajlarını oxuyur (VT ... FS CR)
 * - ORU^R01 nəticələrini minimal parse edir (PID/OBR/OBX)
 * - Serverə JSON POST edir
 * - Cihaza HL7 ACK (AA/AE) qaytarır
 *
 * Java 8 compatible, single class.
 */
public final class Dh76MllpGateway {

    // ===== MLLP control bytes =====
    private static final byte VT = 0x0B; // <VT> start
    private static final byte FS = 0x1C; // <FS> end
    private static final byte CR = 0x0D; // <CR>

    // ===== Default charset =====
    private static final Charset HL7_CHARSET = Charset.forName("UTF-8");

    // ===== Config =====
    private final int listenPort;
    private final String httpEndpoint;
    private final String deviceId;
    private final LabResultsClient resultsClient;
    private final String deviceRecordId;
    private volatile boolean running = true;
    private volatile ServerSocket serverSocket;
    private final List<Socket> activeSockets = Collections.synchronizedList(new ArrayList<Socket>());

    public Dh76MllpGateway(int listenPort, String httpEndpoint, String deviceId, LabResultsClient resultsClient, String deviceRecordId) {
        this.listenPort = listenPort;
        this.httpEndpoint = httpEndpoint;
        this.deviceId = deviceId;
        this.resultsClient = resultsClient;
        this.deviceRecordId = deviceRecordId;
    }

    public void start() throws Exception {
        running = true;
        ServerSocket server = new ServerSocket(listenPort);
        serverSocket = server;
        log("✅ DH-76 MLLP Gateway started on port " + listenPort);
        log("➡ HTTP endpoint: " + httpEndpoint);
        log("➡ deviceId: " + deviceId);

        try {
            while (running) {
                try {
                    final Socket socket = server.accept();
                    if (!running) {
                        try { socket.close(); } catch (Exception ignored) {}
                        break;
                    }
                    activeSockets.add(socket);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(0); // block
                    Thread t = new Thread(() -> handleClient(socket),
                            "dh76-" + listenPort + "-client-" + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
                    t.start();
                } catch (SocketException se) {
                    if (running) throw se;
                    break;
                }
            }
        } finally {
            serverSocket = null;
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}

        synchronized (activeSockets) {
            for (Socket s : activeSockets) {
                try { s.close(); } catch (Exception ignored) {}
            }
            activeSockets.clear();
        }
        log("🛑 DH76 stop requested");
    }

    private void handleClient(Socket socket) {
        String peer = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        log("🔌 Client connected: " + peer);

        try (PushbackInputStream in = new PushbackInputStream(socket.getInputStream(), 1);
             OutputStream out = socket.getOutputStream()) {

            while (running) {
                String hl7 = readMllpMessage(in);
                if (hl7 == null) {
                    log("🔌 Client disconnected: " + peer);
                    return;
                }

                log("📥 HL7-IN (" + peer + ")\n" + prettyHl7(hl7));

                // Parse & forward
                String ack;
                try {
                    Hl7Parsed p = parseHl7(hl7);

                    // yalnız ORU nəticələri forward edirik (istəsən genişləndirərsən)
                    if (p.messageType != null && p.messageType.startsWith("ORU")) {
                        String json = buildUnifiedResultsJson(p, hl7);
                        log("🧾 JSON ---->>>>> " + json);

                        int code = -1;
                        if (resultsClient != null && httpEndpoint != null && !httpEndpoint.trim().isEmpty()) {
                            code = resultsClient.postJson(httpEndpoint, json);
                        }
                        log("🌐 HTTP POST -> " + code);

                        if (code >= 200 && code < 300) {
                            ack = buildAck(hl7, "AA", null);
                        } else {
                            ack = buildAck(hl7, "AE", "HTTP_" + code);
                        }
                    } else {
                        // başqa message-lərə də AA veririk, sadəcə forward etmirik
                        ack = buildAck(hl7, "AA", null);
                    }
                } catch (Exception ex) {
                    log("❌ Parse/Forward error: " + ex.getMessage());
                    ack = buildAck(hl7, "AE", "PARSE_ERROR");
                }

                writeMllpMessage(out, ack);
                log("📤 HL7-ACK-OUT (" + peer + ")\n" + prettyHl7(ack));
            }

        } catch (Exception e) {
            log("❌ Client error (" + peer + "): " + e.getMessage());
        } finally {
            activeSockets.remove(socket);
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ===================== MLLP IO =====================

    private static String readMllpMessage(PushbackInputStream in) throws Exception {
        int b;

        // wait for VT
        do {
            b = in.read();
            if (b < 0) return null;
        } while (b != (VT & 0xFF));

        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        while (true) {
            b = in.read();
            if (b < 0) return null;

            if (b == (FS & 0xFF)) {
                // expect CR after FS (tolerant)
                int cr = in.read();
                if (cr >= 0 && cr != (CR & 0xFF)) {
                    in.unread(cr);
                }
                return new String(buf.toByteArray(), HL7_CHARSET);
            }
            buf.write(b);
        }
    }

    private static void writeMllpMessage(OutputStream out, String hl7) throws Exception {
        out.write(VT);
        out.write(hl7.getBytes(HL7_CHARSET));
        out.write(FS);
        out.write(CR);
        out.flush();
    }

    // ===================== HL7 Parsing (minimal, fixed) =====================

    private static final class Hl7Parsed {
        String sendingApp;
        String sendingFacility;
        String receivingApp;
        String receivingFacility;
        String messageType;   // MSH-9 (ORU^R01)
        String messageCtrlId; // MSH-10
        String version;       // MSH-12

        String patientId;     // PID-3
        String patientName;   // PID-5 (LAST^FIRST^...)
        String dob;           // PID-7 (YYYYMMDD)
        String sex;           // PID-8

        String sampleId;      // OBR-3 or OBR-2 (best-effort)
        String orderCode;     // OBR-4 (01001^Automated Count^99MRC)
        String runDateTime;   // OBR-7 / OBR-14

        List<Result> results = new ArrayList<Result>();
    }

    private static final class Result {
        String code;      // OBX-3.1 (LOINC/cihaz kodu)
        String name;      // OBX-3.2 (WBC və s.)
        String system;    // OBX-3.3 (LN/99MRC)
        String value;     // OBX-5
        String unit;      // OBX-6
        String ref;       // OBX-7
        String flag;      // OBX-8
        String status;    // OBX-11
    }

    private static Hl7Parsed parseHl7(String hl7) {
        Hl7Parsed p = new Hl7Parsed();

        String[] segs = splitSegments(hl7);
        for (String seg : segs) {
            if (seg == null || seg.isEmpty()) continue;

            String[] f = seg.split("\\|", -1);
            if (f.length == 0) continue;

            String name = f[0];

            if ("MSH".equals(name)) {
                // MSH|^~\&|SENDING_APP|SENDING_FAC|RECV_APP|RECV_FAC|...
                p.sendingApp = getField(f, 3);
                p.sendingFacility = getField(f, 4);
                p.receivingApp = getField(f, 5);
                p.receivingFacility = getField(f, 6);
                p.messageType = getField(f, 9);     // ORU^R01
                p.messageCtrlId = getField(f, 10);
                p.version = getField(f, 12);

            } else if ("PID".equals(name)) {
                p.patientId = normalizeCx(getFieldStd(f, 3));
                p.patientName = getFieldStd(f, 5);
                p.dob = getFieldStd(f, 7);
                p.sex = getFieldStd(f, 8);

            } else if ("OBR".equals(name)) {
                // OBR|1||3072835|01001^Automated Count^99MRC||202512...
                // Non-MSH segmentlərdə field nömrəsi birbaşa 1-ci indexdən başlayır:
                // OBR-2 -> f[2], OBR-3 -> f[3]
                String obr2 = getFieldStd(f, 2);
                String obr3 = getFieldStd(f, 3);
                p.sampleId = firstNonEmpty(normalizeCx(obr3), normalizeCx(obr2), p.sampleId);

                p.orderCode = getFieldStd(f, 4);

                // run datetime best-effort (OBR-7 / OBR-14)
                p.runDateTime = firstNonEmpty(getFieldStd(f, 7), getFieldStd(f, 14), p.runDateTime);

            } else if ("OBX".equals(name)) {
                // OBX-3 = identifier  (6690-2^WBC^LN)
                // OBX-5 = value       (6.73)
                // OBX-6 = units       (10*3/uL)
                // OBX-7 = reference   (4,00-10,00)
                // OBX-8 = flags       (~N / H~A / L~A)
                // OBX-11 = status     (F)
                Result r = new Result();

                String obx3 = getFieldStd(f, 3);
                String[] idParts = splitComponents(obx3); // ^

                // İstək üzrə "code" olaraq OBX-3.2 (məs: WBC) istifadə olunur.
                // OBX-3.2 boş olarsa OBX-3.1-ə fallback edirik.
                r.code = firstNonEmpty(safeArr(idParts, 1), safeArr(idParts, 0), "");
                r.name = safeArr(idParts, 1);
                r.system = safeArr(idParts, 2);

                r.value = getFieldStd(f, 5);
                r.unit = getFieldStd(f, 6);
                r.ref = getFieldStd(f, 7);
                r.flag = getFieldStd(f, 8);
                r.status = getFieldStd(f, 11);

                p.results.add(r);
            }
        }

        return p;
    }

    private static String[] splitSegments(String hl7) {
        if (hl7 == null || hl7.isEmpty()) return new String[0];
        return hl7.split("\\r\\n|\\n|\\r");
    }

    private static String[] splitComponents(String field) {
        if (field == null || field.isEmpty()) return new String[0];
        return field.split("\\^", -1);
    }

    private static String safeArr(String[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return "";
        return arr[idx] == null ? "" : arr[idx];
    }

    private static String getField(String[] f, int idx1Based) {
        int idx = idx1Based - 1;
        if (idx < 0 || idx >= f.length) return "";
        return f[idx] == null ? "" : f[idx];
    }

    private static String getFieldStd(String[] f, int idx1Based) {
        int idx = idx1Based;
        if (idx < 0 || idx >= f.length) return "";
        return f[idx] == null ? "" : f[idx];
    }

    private static String normalizeCx(String cx) {
        // CX tipi: "12345^^^FACILITY^MR" -> id = 12345
        if (cx == null) return "";
        int hat = cx.indexOf('^');
        return (hat >= 0) ? cx.substring(0, hat) : cx;
    }

    private static String firstNonEmpty(String a, String b, String c) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        if (c != null && !c.trim().isEmpty()) return c.trim();
        return "";
    }

    // ===================== ACK Builder =====================

    /**
     * HL7 ACK (MSA) qururuq.
     * - ackCode: AA (success) / AE (error) / AR (reject)
     */
    private static String buildAck(String originalHl7, String ackCode, String errText) {
        // Original MSH fields (tolerant split)
        String[] segs = splitSegments(originalHl7);
        String msh = null;
        for (String s : segs) {
            if (s != null && s.startsWith("MSH|")) { msh = s; break; }
        }

        String sendingApp = "GATEWAY";
        String sendingFac = "NM";
        String receivingApp = "";
        String receivingFac = "";
        String msgCtrlId = "";
        String version = "2.3.1";

        if (msh != null) {
            String[] f = msh.split("\\|", -1);
            receivingApp = getField(f, 3); // swap: ack to sender
            receivingFac = getField(f, 4);
            msgCtrlId = getField(f, 10);
            String v = getField(f, 12);
            if (v != null && !v.trim().isEmpty()) version = v.trim();
        }

        String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String ackCtrlId = "ACK" + ts + "_" + (int)(Math.random() * 100000);

        // MSH|^~\&|SendingApp|SendingFac|ReceivingApp|ReceivingFac|TS||ACK^R01|CtrlId|P|2.3.1
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|")
                .append(sendingApp).append("|")
                .append(sendingFac).append("|")
                .append(nullToEmpty(receivingApp)).append("|")
                .append(nullToEmpty(receivingFac)).append("|")
                .append(ts).append("||")
                .append("ACK^R01").append("|")
                .append(ackCtrlId).append("|P|")
                .append(version)
                .append("\r");

        sb.append("MSA|")
                .append(ackCode).append("|")
                .append(nullToEmpty(msgCtrlId));

        if (errText != null && !errText.trim().isEmpty()) {
            sb.append("|").append(errText);
        }
        sb.append("\r");

        return sb.toString();
    }

    // ===================== JSON Builder + HTTP =====================

    private String buildUnifiedResultsJson(Hl7Parsed p, String rawHl7) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"deviceId\":").append(q(deviceId)).append(",");
        sb.append("\"deviceRecordId\":").append(q(deviceRecordId)).append(",");
        sb.append("\"deviceType\":").append(q("DH76")).append(",");
        sb.append("\"patientId\":").append(q(p.patientId)).append(",");
        sb.append("\"sampleId\":").append(q(p.sampleId)).append(",");
        sb.append("\"results\":[");
        for (int i = 0; i < p.results.size(); i++) {
            Result r = p.results.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
                    .append("\"code\":").append(q(r.code)).append(",")
                    .append("\"value\":").append(q(r.value)).append(",")
                    .append("\"unit\":").append(q(r.unit)).append(",")
                    .append("\"flag\":").append(q(r.flag))
                    .append("}");
        }
        sb.append("],");
        sb.append("\"raw\":").append(q(rawHl7));
        sb.append("}");
        return sb.toString();
    }

    // ===================== Helpers =====================

    private static String prettyHl7(String hl7) {
        return hl7.replace("\r", "\r\n");
    }

    private static String formatPatientName(String pid5) {
        // PID-5: LAST^FIRST^MIDDLE...
        if (pid5 == null) return "";
        String[] p = pid5.split("\\^", -1);
        String last = p.length > 0 ? p[0] : "";
        String first = p.length > 1 ? p[1] : "";
        String mid = p.length > 2 ? p[2] : "";
        StringBuilder sb = new StringBuilder();
        if (!last.isEmpty()) sb.append(last);
        if (!first.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(first);
        if (!mid.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(mid);
        return sb.toString().trim();
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void log(String s) {
        System.out.println(s);
    }

    // ===================== Main =====================

    public static void main(String[] args) throws Exception {
        // args: <listenPort> <httpEndpoint> <deviceId>
        String a = "MSH|^~\\&|DH7x|Dymind|||20251219195232||ORU^R01|e7d82f37c96642a48b17867a7f5267dd|P|2.3.1||||||UNICODE\n" +
                "PID|1\n" +
                "PV1|1\n" +
                "OBR|1||3072835|01001^Automated Count^99MRC||20251219125006|20251219125006|||||||20251219125006||||||||||HM||||||||admin\n" +
                "OBX|1|IS|02001^Take Mode^99MRC||A||||||F\n" +
                "OBX|2|IS|02002^Blood Mode^99MRC||W||||||F\n" +
                "OBX|3|IS|02003^Test Mode^99MRC||CBC+DIFF||||||F\n" +
                "OBX|4|NM|30525-0^Age^LN||||||||F\n" +
                "OBX|5|IS|09001^Remark^99MRC||||||||F\n" +
                "OBX|6|IS|03001^Ref Group^99MRC||General||||||F\n" +
                "OBX|7|NM|6690-2^WBC^LN||6.73|10*3/uL|4,00-10,00|~N|||F\n" +
                "OBX|8|NM|770-8^NEU%^LN||64.5|%|50,0-70,0|~N|||F\n" +
                "OBX|9|NM|736-9^LYM%^LN||27.4|%|20,0-40,0|~N|||F\n" +
                "OBX|10|NM|5905-5^MON%^LN||6.5|%|3,0-12,0|~N|||F\n" +
                "OBX|11|NM|713-8^EOS%^LN||1.3|%|0,5-5,0|~N|||F\n" +
                "OBX|12|NM|706-2^BAS%^LN||0.3|%|0,0-1,0|~N|||F\n" +
                "OBX|13|NM|751-8^NEU#^LN||4.35|10*3/uL|2,00-7,00|~N|||F\n" +
                "OBX|14|NM|731-0^LYM#^LN||1.85|10*3/uL|0,80-4,00|~N|||F\n" +
                "OBX|15|NM|742-7^MON#^LN||0.43|10*3/uL|0,12-1,20|~N|||F\n" +
                "OBX|16|NM|711-2^EOS#^LN||0.08|10*3/uL|0,02-0,50|~N|||F\n" +
                "OBX|17|NM|704-7^BAS#^LN||0.02|10*3/uL|0,00-0,10|~N|||F\n" +
                "OBX|18|NM|26477-0^*ALY#^LN||0.02|10*3/uL|0,00-0,20|~N|||F\n" +
                "OBX|19|NM|13046-8^*ALY%^LN||0.2|%|0,0-2,0|~N|||F\n" +
                "OBX|20|NM|11001^*LIC#^99MRC||0.00|10*3/uL|0,00-0,20|~N|||F\n" +
                "OBX|21|NM|11002^*LIC%^99MRC||0.0|%|0,0-2,5|~N|||F\n" +
                "OBX|22|NM|789-8^RBC^LN||4.60|10*6/uL|3,50-5,50|~N|||F\n" +
                "OBX|23|NM|718-7^HGB^LN||11.5|g/dL|11,0-16,0|~N|||F\n" +
                "OBX|24|NM|4544-3^HCT^LN||35.7|%|37,0-54,0|L~A|||F\n" +
                "OBX|25|NM|787-2^MCV^LN||77.7|fL|80,0-100,0|L~A|||F\n" +
                "OBX|26|NM|785-6^MCH^LN||25.1|pg|27,0-34,0|L~A|||F\n" +
                "OBX|27|NM|786-4^MCHC^LN||32.3|g/dL|32,0-36,0|~N|||F\n" +
                "OBX|28|NM|788-0^RDW-CV^LN||16.1|%|11,0-16,0|H~A|||F\n" +
                "OBX|29|NM|21000-5^RDW-SD^LN||45.1|fL|35,0-56,0|~N|||F\n" +
                "OBX|30|NM|777-3^PLT^LN||359|10*3/uL|100-300|H~A|||F\n" +
                "OBX|31|NM|32623-1^MPV^LN||8.0|fL|6,5-12,0|~N|||F\n" +
                "OBX|32|NM|32207-3^PDW^LN||8.3|fL|9,0-17,0|L~A|||F\n" +
                "OBX|33|NM|11003^PCT^99MRC||0.288|%|0,108-0,282|H~A|||F\n" +
                "OBX|34|NM|48386-7^P-LCR^LN||14.4|%|11,0-45,0|~N|||F\n" +
                "OBX|35|NM|34167-7^P-LCC^LN||52|10*9/L|30-90|~N|||F";
        Hl7Parsed p = parseHl7(a);
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        om.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        String json = om.writeValueAsString(p);
        System.out.println(json);


        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 5200;
        String endpoint = (args.length > 1) ? args[1] : "http://192.168.0.51:8080/api/lab/dh76/results";
        String devId = (args.length > 2) ? args[2] : "DH76-1";

        new Dh76MllpGateway(port, endpoint, devId, null, null).start();
    }
}
