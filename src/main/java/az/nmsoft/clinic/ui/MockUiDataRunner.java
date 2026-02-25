package az.nmsoft.clinic.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lab.ui.mock", havingValue = "true")
public class MockUiDataRunner implements CommandLineRunner {
    private final MockUiDataService mockUiDataService;

    public MockUiDataRunner(MockUiDataService mockUiDataService) {
        this.mockUiDataService = mockUiDataService;
    }

    @Override
    public void run(String... args) {
        mockUiDataService.startMockDevices();
    }
}
