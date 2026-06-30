package com.itsjool.aperture.spi;

public record ApiKeyCreationResult(ApiKeyRecord record, String secret) {
    @Override
    public String toString() {
        return "ApiKeyCreationResult[record=" + record + ", secret=[REDACTED]]";
    }
}
