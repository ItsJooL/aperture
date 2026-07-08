package com.itsjool.aperture.engine.hook;

import java.util.List;

public record HookSemantics(
    String type,
    String phase,
    boolean async,
    String onFailure,
    List<String> operations,
    boolean enrichment
) {}
