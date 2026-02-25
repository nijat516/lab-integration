package az.nmsoft.clinic.ui;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.PrintStream;

@Component
public class LogCapture implements InitializingBean {
    private final LogStore store;
    private final DeviceRegistry deviceRegistry;

    public LogCapture(LogStore store, DeviceRegistry deviceRegistry) {
        this.store = store;
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new TeeOutputStream(originalOut, store, deviceRegistry), true, "UTF-8"));
        System.setErr(new PrintStream(new TeeOutputStream(originalErr, store, deviceRegistry), true, "UTF-8"));
    }

    private static final class TeeOutputStream extends OutputStream {
        private final PrintStream original;
        private final LogStore store;
        private final DeviceRegistry deviceRegistry;
        private final ThreadLocal<StringBuilder> buffers = new ThreadLocal<StringBuilder>() {
            @Override
            protected StringBuilder initialValue() {
                return new StringBuilder();
            }
        };

        private TeeOutputStream(PrintStream original, LogStore store, DeviceRegistry deviceRegistry) {
            this.original = original;
            this.store = store;
            this.deviceRegistry = deviceRegistry;
        }

        @Override
        public void write(int b) {
            original.write(b);
            StringBuilder sb = buffers.get();
            if (b == '\n') {
                String line = sb.toString();
                sb.setLength(0);
                if (!line.isEmpty()) {
                    store.append(currentKey(), line);
                }
                return;
            }
            if (b != '\r') {
                sb.append((char) b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        private String currentKey() {
            String name = Thread.currentThread().getName();
            return deviceRegistry.resolveKey(name);
        }
    }
}
