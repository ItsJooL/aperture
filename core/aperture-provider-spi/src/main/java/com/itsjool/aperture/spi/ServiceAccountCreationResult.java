package com.itsjool.aperture.spi;

public record ServiceAccountCreationResult(ServiceAccountRecord record, String secret) {
    @Override
    public String toString() {
        return "ServiceAccountCreationResult[record=" + record + ", secret=[REDACTED]]";
    }
}
