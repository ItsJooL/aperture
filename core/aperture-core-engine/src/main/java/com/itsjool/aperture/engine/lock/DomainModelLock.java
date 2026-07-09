package com.itsjool.aperture.engine.lock;

import com.itsjool.aperture.engine.model.OneOfDef;

import java.util.List;

public record DomainModelLock(List<OneOfDef> oneOfs) {
    public DomainModelLock {
        oneOfs = oneOfs != null ? List.copyOf(oneOfs) : List.of();
    }
}
