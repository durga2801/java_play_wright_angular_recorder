package com.example.angularrecorder;

import com.example.angularrecorder.model.RecordedEvent;
import com.example.angularrecorder.model.Recording;
import com.example.angularrecorder.service.PlaywrightAngularRecorder;
import com.example.angularrecorder.service.RecordingWriter;
import com.example.angularrecorder.util.ProjectRootResolver;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class RecorderMain {

    public static void main(String[] args) throws Exception {

        String url = readArg(args, 0, "http://localhost:4200");
        String project = readArg(args, 1, "angular-app");
        String runId = readArg(args, 2, null);

        if (runId == null || runId.isBlank()) {
            runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Path projectRoot = ProjectRootResolver.findCurrentMavenProjectRoot();

        System.out.println("Project root : " + projectRoot);
        System.out.println("Project      : " + project);
        System.out.println("Run ID       : " + runId);
        System.out.println("URL          : " + url);

        List<RecordedEvent> events;

        try (PlaywrightAngularRecorder recorder = new PlaywrightAngularRecorder()) {
            recorder.open(url);

            new Scanner(System.in).nextLine();

            events = recorder.readEvents();
        }

        Recording recording = new Recording(
                project,
                runId,
                url,
                Instant.now().toString(),
                events
        );

        Path output = new RecordingWriter().write(projectRoot, recording);

        System.out.println();
        System.out.println("Recording complete.");
        System.out.println("Events : " + events.size());
        System.out.println("Output : " + output);
        System.out.println("JSON   : " + output.resolve("recording.json"));
        System.out.println("Copilot: " + output.resolve("COPILOT-REQUESTS.md"));
    }

    private static String readArg(String[] args, int index, String defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        String value = args[index];
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
