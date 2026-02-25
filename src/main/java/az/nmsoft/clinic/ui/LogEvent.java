package az.nmsoft.clinic.ui;

public class LogEvent {
    public String key;
    public String message;
    public long ts;

    public LogEvent() {}

    public LogEvent(String key, String message, long ts) {
        this.key = key;
        this.message = message;
        this.ts = ts;
    }
}
