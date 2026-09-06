package com.example.angularrecorder.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordedEvent(
        long sequence,
        long timestamp,
        long stage,
        String actionType,
        String inputType,
        String input,
        IdentifyBy identifyBy,
        RecordedValue value,
        ContextInfo context,
        WaitInfo waitInfo,
        String url
) {
    public RecordedEvent withSequence(long newSequence) {
        return new RecordedEvent(
                newSequence,
                timestamp,
                stage,
                actionType,
                inputType,
                input,
                identifyBy,
                value,
                context,
                waitInfo,
                url
        );
    }
}
