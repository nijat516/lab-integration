package az.nmsoft.clinic.integration;

public class CobasC311OrderRequest {
    public String sampleId;
    public String deviceId;

    public CobasC311OrderRequest(String sampleId, String deviceId) {
        this.sampleId = sampleId;
        this.deviceId = deviceId;
    }
}
