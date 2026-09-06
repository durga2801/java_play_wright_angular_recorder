package com.example.angularrecorder.model;

import java.util.List;

public record Recording(
        String project,
        String runId,
        String startUrl,
        String recordedAt,
        List<RecordedEvent> events
) {}
