package com.example.angularrecorder.service;

import com.example.angularrecorder.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlaywrightAngularRecorder implements AutoCloseable {

    private static final String PREFIX =
            "__ANGULAR_RECORDER_EVENT__";

    private final ObjectMapper mapper =
            new ObjectMapper();

    private final List<JsonNode> rawEvents =
            new CopyOnWriteArrayList<>();

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    public void open(String url) {

        playwright = Playwright.create();

        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(false)
        );

        context = browser.newContext();

        // Direct browser -> Java event bridge. Avoids relying on console output.
        context.exposeBinding("__angularRecorderEmit", (source, args) -> {
            if (args == null || args.length == 0 || args[0] == null) {
                return null;
            }
            try {
                JsonNode node = mapper.valueToTree(args[0]);
                rawEvents.add(node);
                System.out.println(PREFIX + mapper.writeValueAsString(node));
            } catch (Exception ex) {
                System.err.println("Unable to capture recorder event: " + ex.getMessage());
            }
            return null;
        });

        /*
         * Install recorder before every Angular page/reload/navigation.
         */
        context.addInitScript(loadRecordingScript());

        page = context.newPage();

        page.onConsoleMessage(message -> {

            String text = message.text();

            if (text == null ||
                    !text.startsWith(PREFIX)) {
                return;
            }

            String json =
                    text.substring(PREFIX.length());

            try {

                JsonNode node =
                        mapper.readTree(json);

                rawEvents.add(node);

                System.out.printf(
                        "[RECORDED] stage=%s action=%-20s input=%s value=%s%n",
                        node.path("stage").asText(),
                        text(node, "actionType"),
                        text(node, "input"),
                        valuePreview(node)
                );

            } catch (Exception ex) {

                System.err.println(
                        "Unable to parse recorder event: "
                                + ex.getMessage()
                );
            }
        });

        page.navigate(url);

        page.waitForLoadState();

        System.out.println(
                "Angular recorder opened: " + url
        );

        System.out.println(
                "Use application normally."
        );

        System.out.println(
                "Press ENTER when recording is complete."
        );
    }

    public List<RecordedEvent> readEvents() {

        mergeFallbackEvents();

        List<RecordedEvent> result =
                new ArrayList<>();

        long sequence = 1;

        for (JsonNode node : dedupeRawEvents(
                rawEvents
        )) {

            result.add(
                    toEvent(
                            sequence++,
                            node
                    )
            );
        }

        return new EventNormalizer()
                .normalize(result);
    }

    /*
     * Removes only near-identical duplicate DOM recordings. This is a
     * recorder-level safety net and does not depend on RecordedEvent model
     * accessor names.
     */
    private List<JsonNode> dedupeRawEvents(
            List<JsonNode> events) {

        List<JsonNode> deduped =
                new ArrayList<>();

        String previousKey = null;
        long previousTimestamp = Long.MIN_VALUE;

        for (JsonNode node : events) {

            String key =
                    text(node, "actionType")
                            + "|"
                            + text(node, "inputType")
                            + "|"
                            + text(node, "input")
                            + "|"
                            + valuePreview(node);

            long timestamp =
                    node.path("timestamp")
                            .asLong(0L);

            if (Objects.equals(
                    previousKey,
                    key
            )
                    && timestamp >= previousTimestamp
                    && timestamp - previousTimestamp < 1500) {

                continue;
            }

            deduped.add(node);
            previousKey = key;
            previousTimestamp = timestamp;
        }

        return deduped;
    }


    private RecordedEvent toEvent(
            long sequence,
            JsonNode node) {

        IdentifyBy identifyBy = null;

        JsonNode identifyNode =
                node.get("identifyBy");

        if (identifyNode != null &&
                !identifyNode.isNull()) {

            Map<String, String> candidates =
                    new LinkedHashMap<>();

            JsonNode candidateNode =
                    identifyNode.get("candidates");

            if (candidateNode != null &&
                    candidateNode.isObject()) {

                candidateNode
                        .fields()
                        .forEachRemaining(entry -> {

                            String value =
                                    entry
                                            .getValue()
                                            .asText("");

                            if (!value.isBlank()) {

                                candidates.put(
                                        entry.getKey(),
                                        value
                                );
                            }
                        });
            }

            identifyBy =
                    new IdentifyBy(
                            text(
                                    identifyNode,
                                    "preferredStrategy"
                            ),
                            text(
                                    identifyNode,
                                    "preferredValue"
                            ),
                            candidates
                    );
        }

        RecordedValue value = null;

        JsonNode valueNode =
                node.get("value");

        if (valueNode != null &&
                !valueNode.isNull()) {

            if (valueNode.isTextual()) {

                value =
                        new RecordedValue(
                                valueNode.asText(),
                                null,
                                null,
                                null,
                                null,
                                null
                        );

            } else {

                value =
                        new RecordedValue(
                                text(
                                        valueNode,
                                        "raw"
                                ),
                                text(
                                        valueNode,
                                        "searchText"
                                ),
                                text(
                                        valueNode,
                                        "selectedValue"
                                ),
                                text(
                                        valueNode,
                                        "selectedText"
                                ),
                                text(
                                        valueNode,
                                        "fileName"
                                ),
                                bool(
                                        valueNode,
                                        "checked"
                                )
                        );
            }
        }

        ContextInfo contextInfo = null;

        JsonNode contextNode =
                node.get("context");

        if (contextNode != null &&
                !contextNode.isNull()) {

            contextInfo =
                    new ContextInfo(
                            text(
                                    contextNode,
                                    "type"
                            ),
                            text(
                                    contextNode,
                                    "name"
                            )
                    );
        }

        WaitInfo waitInfo = null;

        JsonNode waitNode =
                node.get("wait");

        if (waitNode != null &&
                !waitNode.isNull()) {

            waitInfo =
                    new WaitInfo(
                            text(
                                    waitNode,
                                    "type"
                            ),
                            text(
                                    waitNode,
                                    "target"
                            ),
                            text(
                                    waitNode,
                                    "role"
                            )
                    );
        }

        return new RecordedEvent(
                sequence,
                node.path("timestamp")
                        .asLong(
                                System.currentTimeMillis()
                        ),
                node.path("stage")
                        .asLong(1L),
                text(
                        node,
                        "actionType"
                ),
                text(
                        node,
                        "inputType"
                ),
                text(
                        node,
                        "input"
                ),
                identifyBy,
                value,
                contextInfo,
                waitInfo,
                text(
                        node,
                        "url"
                )
        );
    }

    private void mergeFallbackEvents() {

        if (page == null ||
                page.isClosed()) {
            return;
        }

        try {

            Object data =
                    page.evaluate(
                            "() => window.__angularRecorderEvents || []"
                    );

            JsonNode array =
                    mapper.valueToTree(data);

            if (!array.isArray()) {
                return;
            }

            Set<String> fingerprints =
                    new HashSet<>();

            for (JsonNode event : rawEvents) {

                fingerprints.add(
                        fingerprint(event)
                );
            }

            for (JsonNode event : array) {

                if (fingerprints.add(
                        fingerprint(event))) {

                    rawEvents.add(event);
                }
            }

        } catch (Exception ignored) {
        }
    }

    private String fingerprint(
            JsonNode node) {

        String eventId =
                text(node, "eventId");

        if (eventId != null) {
            return "eventId|" + eventId;
        }

        return node.path("timestamp").asText()
                + "|"
                + text(
                node,
                "actionType"
        )
                + "|"
                + text(
                node,
                "input"
        )
                + "|"
                + valuePreview(node);
    }

    private String valuePreview(
            JsonNode node) {

        JsonNode value =
                node.get("value");

        if (value == null ||
                value.isNull()) {
            return "";
        }

        if (value.isTextual()) {
            return value.asText();
        }

        String[] fields = {
                "raw",
                "searchText",
                "selectedValue",
                "selectedText",
                "fileName"
        };

        for (String field : fields) {

            String result =
                    text(
                            value,
                            field
                    );

            if (result != null &&
                    !result.isBlank()) {

                return result;
            }
        }

        return "";
    }

    private String text(
            JsonNode node,
            String name) {

        if (node == null) {
            return null;
        }

        JsonNode child =
                node.get(name);

        if (child == null ||
                child.isNull()) {
            return null;
        }

        String value =
                child.asText();

        return value.isBlank()
                ? null
                : value;
    }

    private Boolean bool(
            JsonNode node,
            String name) {

        if (node == null ||
                !node.has(name) ||
                node.get(name).isNull()) {

            return null;
        }

        return node
                .get(name)
                .asBoolean();
    }

    @Override
    public void close() {

        if (context != null) {
            context.close();
        }

        if (browser != null) {
            browser.close();
        }

        if (playwright != null) {
            playwright.close();
        }
    }

    private static String loadRecordingScript() {
        try (var in = PlaywrightAngularRecorder.class.getResourceAsStream(
                "/recorder/playwright-angular-recorder.js")) {
            if (in == null) {
                throw new IllegalStateException(
                        "Recorder script not found: /recorder/playwright-angular-recorder.js");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to load recorder script", e);
        }
    }

}