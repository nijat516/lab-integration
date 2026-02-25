package az.nmsoft.clinic.integration;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class LabResultsClient {
    private final WebClient webClient = WebClient.builder().build();

    public int postJson(String url, String json) {
        if (url == null || url.trim().isEmpty()) return -1;
        try {
            Integer code = webClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(json)
                    .retrieve()
                    .toBodilessEntity()
                    .map(resp -> resp.getStatusCode().value())
                    .block();
            return code == null ? -1 : code;
        } catch (Exception e) {
            System.err.println("❌ Results POST error: " + e.getMessage());
            return -1;
        }
    }
}
