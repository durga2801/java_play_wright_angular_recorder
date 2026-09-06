package com.example.angularrecorder.model;

public record RecordedValue(
        String raw,
        String searchText,
        String selectedValue,
        String selectedText,
        String fileName,
        Boolean checked
) {}
