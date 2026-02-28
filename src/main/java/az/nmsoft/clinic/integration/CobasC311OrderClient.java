package az.nmsoft.clinic.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Objects;

@Component
public class CobasC311OrderClient {
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${lab.devices.c311.orderUrl:}")
    private String orderUrl;

    public CobasC311OrderResponse fetchOrder(CobasC311OrderRequest req) {
        if (req == null || orderUrl == null || orderUrl.trim().isEmpty()) {
            System.out.println("⚠️ C311 order fetch skipped. req/orderUrl missing.");
            return null;
        }
        try {
            String reqJson = toJson(req);
            System.out.println("📤 C311 ORDER REQUEST URL: " + orderUrl);
            System.out.println("📤 C311 ORDER REQUEST BODY: " + reqJson);
            CobasC311OrderResponse response = webClient.post()
                    .uri(orderUrl)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<CobasC311OrderResponse>() {})
                    .block();

            System.out.println("📥 C311 ORDER RESPONSE BODY: " + toJson(response));
            return response;
        } catch (Exception e) {
            System.err.println("❌ C311 order fetch error: " + e.getMessage());
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
