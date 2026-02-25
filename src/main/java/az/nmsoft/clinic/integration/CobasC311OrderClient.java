package az.nmsoft.clinic.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class CobasC311OrderClient {
    private final WebClient webClient = WebClient.builder().build();

    @Value("${lab.devices.c311.orderUrl:}")
    private String orderUrl;

    public CobasC311OrderResponse fetchOrder(CobasC311OrderRequest req) {
        if (req == null || orderUrl == null || orderUrl.trim().isEmpty()) {
            return null;
        }
        try {
            return webClient.post()
                    .uri(orderUrl)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<CobasC311OrderResponse>() {})
                    .block();
        } catch (Exception e) {
            System.err.println("❌ C311 order fetch error: " + e.getMessage());
            return null;
        }
    }
}
