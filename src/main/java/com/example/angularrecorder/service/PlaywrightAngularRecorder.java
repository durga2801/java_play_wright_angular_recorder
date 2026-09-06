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

        /*
         * Very important:
         * installs recorder before every Angular page/reload/navigation.
         */
        context.addInitScript(RECORDING_SCRIPT);

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

    private static final String RECORDING_SCRIPT = """
(() => {

    if (window.__angularRecorderInstalled) {
        return;
    }

    window.__angularRecorderInstalled = true;

    window.__angularRecorderEvents = [];

    const PREFIX =
        '__ANGULAR_RECORDER_EVENT__';

    const timers =
        new WeakMap();

    const pending =
        new WeakMap();

    let currentStage = 1;

    let lastUrl =
        location.href;

    let lastMutationAt =
        Date.now();

    /*
     * Tracks the logical Angular form control currently being used.
     * This is intentionally the OUTER bound component (controlName /
     * formControlName / ngModel), not an internal button/input/calendar.
     */
    let activeLogicalControl = null;

    const lastLogicalEmissions =
        new WeakMap();

    /*
     * State for custom search/select and multi-select controls.
     * Keyed by the OUTER logical control so internal search inputs,
     * popup options and checkbox items stay associated with one field.
     */
    const selectionStates =
        new WeakMap();

    const multiSelectTimers =
        new WeakMap();

    let eventCounter = 0;



    /* =========================================================
       BASIC HELPERS
       ========================================================= */

    function clean(value) {

        if (value == null) {
            return null;
        }

        const result =
            String(value)
                .replace(/\\s+/g, ' ')
                .trim();

        return result || null;
    }



    function shortText(element) {

        if (!element) {
            return null;
        }

        const text =
            clean(
                element.innerText ||
                element.textContent
            );

        if (!text ||
            text.length > 180) {

            return null;
        }

        return text;
    }



    function eventElement(event) {

        const path =
            event &&
            event.composedPath
                ? event.composedPath()
                : [];

        for (const item of path) {

            if (item &&
                item.nodeType ===
                Node.ELEMENT_NODE) {

                return item;
            }
        }

        return event
            ? event.target
            : null;
    }



    function eventPath(event) {

        if (event &&
            event.composedPath) {

            return event
                .composedPath()
                .filter(
                    x =>
                        x &&
                        x.nodeType ===
                        Node.ELEMENT_NODE
                );
        }

        return [];
    }



    function ancestors(element) {

        const result = [];

        let current =
            element;

        while (current &&
               current !==
               document.documentElement) {

            result.push(current);

            current =
                current.parentElement;
        }

        return result;
    }



    /* =========================================================
       FIND ACTUAL CONTROL
       ========================================================= */

    function isNativeOrSemanticControl(element) {

        if (!element ||
            !element.matches) {

            return false;
        }

        return element.matches(`
            input,
            textarea,
            select,
            button,
            a,
            [contenteditable="true"],
            [role="textbox"],
            [role="searchbox"],
            [role="combobox"],
            [role="checkbox"],
            [role="radio"],
            [role="switch"],
            [role="button"],
            [role="link"],
            [role="option"],
            [role="menuitem"],
            [role="slider"],
            [role="spinbutton"]
        `);
    }



    function closestMeaningful(
        event) {

        const path =
            eventPath(event);

        /*
         * First prefer actual native/semantic input.
         */
        for (const node of path) {

            if (isNativeOrSemanticControl(
                node)) {

                return node;
            }
        }

        /*
         * Then custom Angular component.
         */
        for (const node of path) {

            if (isLogicalAngularHost(
                node)) {

                return node;
            }
        }

        return eventElement(event);
    }



    /* =========================================================
       GENERIC ANGULAR CUSTOM CONTROL DETECTION
       ========================================================= */

    function isLogicalAngularHost(
        element) {

        if (!element ||
            !element.getAttribute) {

            return false;
        }

        const tag =
            (
                element.tagName ||
                ''
            ).toLowerCase();

        /*
         * Generic Angular/custom component.
         *
         * Example:
         *
         * mt-dropdown
         * dcrm-dcrm-datepicker
         * abc-customer-selector
         *
         * No component name is hardcoded.
         */
        if (tag.includes('-')) {
            return true;
        }

        if (
            element.hasAttribute(
                'formControlName'
            ) ||
            element.hasAttribute(
                'formcontrolname'
            ) ||
            element.hasAttribute(
                'controlName'
            ) ||
            element.hasAttribute(
                'controlname'
            ) ||
            element.hasAttribute(
                'ngModel'
            ) ||
            element.hasAttribute(
                'ngmodel'
            )
        ) {

            return true;
        }

        return false;
    }



    function hasControlBinding(element) {

        if (!element ||
            !element.getAttribute) {

            return false;
        }

        return (
            element.hasAttribute('formControlName') ||
            element.hasAttribute('formcontrolname') ||
            element.hasAttribute('controlName') ||
            element.hasAttribute('controlname') ||
            element.hasAttribute('ngModel') ||
            element.hasAttribute('ngmodel') ||
            element.hasAttribute('ng-reflect-name')
        );
    }



    function findLogicalAngularHost(
        nativeElement,
        event) {

        const nodes = [
            ...eventPath(event),
            ...ancestors(nativeElement)
        ];

        /*
         * Most important rule for custom Angular controls:
         * prefer the component/div that owns the actual Angular binding.
         *
         * Example:
         * <dcrm-dcrm-datepicker controlName="detectedDate">
         *     <button aria-label="Open picker">...</button>
         *     <mt-calendar>...</mt-calendar>
         * </dcrm-dcrm-datepicker>
         *
         * The button/calendar are implementation details.  The bound
         * datepicker is the logical input.
         */
        for (const node of nodes) {

            if (hasControlBinding(node)) {
                return node;
            }
        }

        /*
         * Fallback for custom components without an exposed Angular
         * binding attribute.
         */
        for (const node of nodes) {

            if (node === nativeElement) {
                continue;
            }

            if (isLogicalAngularHost(node)) {
                return node;
            }
        }

        return null;
    }



    /* =========================================================
       CONTROL NAME
       ========================================================= */

    function controlName(
        nativeElement,
        customHost) {

        const elements = [
            nativeElement,
            customHost
        ];

        for (const element of elements) {

            if (!element ||
                !element.getAttribute) {

                continue;
            }

            const value =
                clean(
                    element.getAttribute(
                        'formControlName'
                    ) ||
                    element.getAttribute(
                        'formcontrolname'
                    ) ||
                    element.getAttribute(
                        'controlName'
                    ) ||
                    element.getAttribute(
                        'controlname'
                    ) ||
                    element.getAttribute(
                        'name'
                    ) ||
                    element.getAttribute(
                        'ng-reflect-name'
                    )
                );

            if (value) {
                return value;
            }
        }

        return null;
    }



    /* =========================================================
       LABEL DETECTION
       ========================================================= */

    function labelledBy(
        element) {

        if (!element ||
            !element.getAttribute) {

            return null;
        }

        const ids =
            clean(
                element.getAttribute(
                    'aria-labelledby'
                )
            );

        if (!ids) {
            return null;
        }

        return clean(
            ids
                .split(/\\s+/)
                .map(
                    id =>
                        document.getElementById(
                            id
                        )
                )
                .filter(Boolean)
                .map(
                    element =>
                        element.innerText ||
                        element.textContent
                )
                .join(' ')
        );
    }



    function cleanLabelText(
        label) {

        if (!label) {
            return null;
        }

        const clone =
            label.cloneNode(true);

        /*
         * Remove tooltip/help/error decorations.
         */
        clone
            .querySelectorAll(`
                [role="tooltip"],
                [class*="tooltip"],
                [class*="error"],
                mat-icon,
                svg,
                small
            `)
            .forEach(
                element =>
                    element.remove()
            );

        return clean(
            clone.textContent
        );
    }



    function associatedLabel(
        element) {

        if (!element) {
            return null;
        }

        if (element.labels &&
            element.labels.length) {

            const result =
                clean(
                    Array.from(
                        element.labels
                    )
                    .map(
                        cleanLabelText
                    )
                    .filter(Boolean)
                    .join(' ')
                );

            if (result) {
                return result;
            }
        }

        const id =
            clean(
                element.id
            );

        if (id) {

            try {

                const label =
                    document.querySelector(
                        `label[for="${CSS.escape(id)}"]`
                    );

                if (label) {

                    return cleanLabelText(
                        label
                    );
                }

            } catch (_) {
            }
        }

        const wrapping =
            element.closest
                ? element.closest(
                    'label'
                )
                : null;

        if (wrapping) {

            const result =
                cleanLabelText(
                    wrapping
                );

            if (result) {
                return result;
            }
        }

        const ariaLabelled =
            labelledBy(element);

        if (ariaLabelled) {
            return ariaLabelled;
        }

        return null;
    }



    function findFormContainer(
        nativeElement,
        customHost) {

        const source =
            customHost ||
            nativeElement;

        if (!source ||
            !source.closest) {

            return null;
        }

        return source.closest(`
            .form-group,
            .lmn-form-group,
            .form-field,
            .field,
            .mat-mdc-form-field,
            .mat-form-field,
            .p-field,
            .p-float-label,
            [class*="form-group"],
            [class*="form-field"]
        `);
    }



    function labelFromContainer(
        nativeElement,
        customHost) {

        const container =
            findFormContainer(
                nativeElement,
                customHost
            );

        if (!container) {
            return null;
        }

        /*
         * Prefer direct form label.
         */
        const labels =
            Array.from(
                container.querySelectorAll(
                    'label'
                )
            );

        for (const label of labels) {

            const text =
                cleanLabelText(
                    label
                );

            if (text) {
                return text;
            }
        }

        return null;
    }



    function visibleLabel(
        nativeElement,
        customHost) {

        return clean(
            associatedLabel(
                nativeElement
            ) ||

            nativeElement
                ?.getAttribute
                ?.('aria-label') ||

            labelledBy(
                nativeElement
            ) ||

            customHost
                ?.getAttribute
                ?.('aria-label') ||

            labelledBy(
                customHost
            ) ||

            labelFromContainer(
                nativeElement,
                customHost
            )
        );
    }



    /* =========================================================
       CONTROL RESOLUTION
       ========================================================= */

    function resolveControl(
        event) {

        const nativeElement =
            closestMeaningful(
                event
            );

        const customHost =
            findLogicalAngularHost(
                nativeElement,
                event
            );

        const label =
            visibleLabel(
                nativeElement,
                customHost
            );

        const name =
            controlName(
                nativeElement,
                customHost
            );

        return {
            nativeElement,
            clickedElement: eventElement(event),
            customHost,
            label,
            controlName: name
        };
    }



    /* =========================================================
       INPUT TYPE DETECTION
       ========================================================= */

    function detectInputType(
        control) {

        const element =
            control.nativeElement;

        const host =
            control.customHost;

        const tag =
            (
                element
                    ?.tagName ||
                ''
            ).toLowerCase();

        const role =
            clean(
                element
                    ?.getAttribute
                    ?.('role') ||

                host
                    ?.getAttribute
                    ?.('role')
            );

        const type =
            clean(
                element
                    ?.getAttribute
                    ?.('type')
            )?.toLowerCase();

        if (type === 'file') {
            return 'FILE';
        }

        if (
            type === 'checkbox' ||
            role === 'checkbox'
        ) {

            return 'CHECKBOX';
        }

        if (
            type === 'radio' ||
            role === 'radio'
        ) {

            return 'RADIO';
        }

        if (role === 'switch') {
            return 'TOGGLE';
        }

        if (
            type === 'date' ||
            type === 'datetime-local' ||
            type === 'month' ||
            type === 'week' ||
            type === 'time'
        ) {

            return 'DATE';
        }

        if (
            type === 'range' ||
            role === 'slider'
        ) {

            return 'SLIDER';
        }

        if (tag === 'textarea') {
            return 'TEXTAREA';
        }

        if (tag === 'select') {
            return 'SELECT';
        }

        if (
            role === 'combobox' ||
            role === 'searchbox' ||
            element
                ?.getAttribute
                ?.('aria-autocomplete')
        ) {

            return 'AUTOCOMPLETE';
        }

        if (
            element
                ?.isContentEditable
        ) {

            return 'RICH_TEXT';
        }

        /*
         * Inspect the OUTER logical custom component before classifying
         * an internal trigger button. This keeps datepickers, dropdowns
         * and similar composite controls as one input.
         */
        if (host) {

            const hasDate =
                host.querySelector(
                    'input[type="date"],input[type="datetime-local"],' +
                    'input[type="month"],input[type="week"],input[type="time"]'
                );

            if (hasDate) {
                return 'DATE';
            }

            const hostName =
                (host.tagName || '')
                    .toLowerCase();

            const dateHint =
                clean(
                    host.getAttribute?.('aria-label') ||
                    host.getAttribute?.('controlName') ||
                    host.getAttribute?.('controlname') ||
                    host.getAttribute?.('formControlName') ||
                    host.getAttribute?.('formcontrolname')
                );

            if (hostName.includes('date') ||
                hostName.includes('calendar') ||
                /(^|[^a-z])(date|dob|day|month|year)([^a-z]|$)/i
                    .test(dateHint || '')) {

                return 'DATE';
            }

            const hasNativeSelect =
                host.querySelector('select');

            if (hasNativeSelect) {
                return 'SELECT';
            }
        }

        if (type === 'password') {
            return 'PASSWORD';
        }

        if (type === 'email') {
            return 'EMAIL';
        }

        if (type === 'number') {
            return 'NUMBER';
        }

        if (type === 'tel') {
            return 'TEL';
        }

        if (type === 'url') {
            return 'URL';
        }

        if (type === 'search') {
            return 'SEARCH';
        }

        if (
            tag === 'input' ||
            role === 'textbox'
        ) {

            return 'TEXT';
        }

        if (
            tag === 'button' ||
            role === 'button'
        ) {

            return 'BUTTON';
        }

        if (
            tag === 'a' ||
            role === 'link'
        ) {

            return 'LINK';
        }

        if (role === 'option') {
            return 'OPTION';
        }

        /*
         * Generic custom component inference.
         */
        if (host) {

            const hasFile =
                host.querySelector(
                    'input[type="file"]'
                );

            if (hasFile) {
                return 'FILE';
            }

            const hasCheckbox =
                host.querySelector(
                    'input[type="checkbox"],[role="checkbox"]'
                );

            if (hasCheckbox) {
                return 'CHECKBOX';
            }

            const hasRadio =
                host.querySelector(
                    'input[type="radio"],[role="radio"]'
                );

            if (hasRadio) {
                return 'RADIO';
            }

            const hasSearch =
                host.querySelector(
                    'input[type="search"],[role="searchbox"],[role="combobox"],input[aria-autocomplete]'
                );

            if (hasSearch) {
                return 'AUTOCOMPLETE';
            }

            const hasText =
                host.querySelector(
                    'input,textarea,[role="textbox"]'
                );

            if (hasText) {
                return 'TEXT';
            }
        }

        return 'CUSTOM';
    }



    /* =========================================================
       IDENTIFICATION
       ========================================================= */

    function candidates(
        control) {

        const result = {};

        const element =
            control.nativeElement;

        const host =
            control.customHost;

        if (control.label) {
            result.LABEL =
                control.label;
        }

        const ariaLabel =
            clean(
                element
                    ?.getAttribute
                    ?.('aria-label') ||

                host
                    ?.getAttribute
                    ?.('aria-label')
            );

        if (ariaLabel) {

            result.ARIA_LABEL =
                ariaLabel;
        }

        const accessible =
            clean(
                associatedLabel(
                    element
                ) ||

                ariaLabel ||

                labelledBy(
                    element
                )
            );

        if (accessible) {

            result.ACCESSIBLE_NAME =
                accessible;
        }

        if (control.controlName) {

            /*
             * Determine whether it came from formControlName
             * or custom controlName.
             */
            const formControl =
                clean(
                    element
                        ?.getAttribute
                        ?.('formControlName') ||

                    element
                        ?.getAttribute
                        ?.('formcontrolname') ||

                    host
                        ?.getAttribute
                        ?.('formControlName') ||

                    host
                        ?.getAttribute
                        ?.('formcontrolname')
                );

            if (formControl) {

                result.FORM_CONTROL_NAME =
                    formControl;

            } else {

                result.CONTROL_NAME =
                    control.controlName;
            }
        }

        const name =
            clean(
                element
                    ?.getAttribute
                    ?.('name') ||

                host
                    ?.getAttribute
                    ?.('name')
            );

        if (name) {

            result.NAME =
                name;
        }

        const id =
            clean(
                element?.id ||
                host?.id
            );

        if (id) {

            result.ID =
                id;
        }

        const role =
            clean(
                element
                    ?.getAttribute
                    ?.('role') ||

                host
                    ?.getAttribute
                    ?.('role')
            );

        if (role) {

            result.ROLE =
                role;
        }

        const placeholder =
            clean(
                element
                    ?.getAttribute
                    ?.('placeholder')
            );

        /*
         * Placeholder is metadata only.
         * It never creates a second event.
         */
        if (placeholder) {

            result.PLACEHOLDER =
                placeholder;
        }

        const testId =
            clean(
                element
                    ?.getAttribute
                    ?.('data-testid') ||

                element
                    ?.getAttribute
                    ?.('data-test') ||

                host
                    ?.getAttribute
                    ?.('data-testid') ||

                host
                    ?.getAttribute
                    ?.('data-test')
            );

        if (testId) {

            result.DATA_TESTID =
                testId;
        }

        if (host) {

            const component =
                clean(
                    host.tagName
                )?.toLowerCase();

            if (component) {

                result.COMPONENT =
                    component;
            }
        }

        return result;
    }



    function semanticInputName(
        control) {

        const c =
            candidates(
                control
            );

        /*
         * Label is highest priority.
         */
        return clean(
            c.LABEL ||
            c.ARIA_LABEL ||
            c.ACCESSIBLE_NAME ||
            c.FORM_CONTROL_NAME ||
            c.CONTROL_NAME ||
            c.NAME ||
            c.ID ||
            c.PLACEHOLDER ||
            c.COMPONENT ||
            'unnamed'
        );
    }



    function identifyBy(
        control) {

        const c =
            candidates(control);

        const priority = [
            'LABEL',
            'ARIA_LABEL',
            'ACCESSIBLE_NAME',
            'FORM_CONTROL_NAME',
            'CONTROL_NAME',
            'NAME',
            'ID',
            'ROLE',
            'DATA_TESTID',
            'PLACEHOLDER',
            'COMPONENT'
        ];

        for (const strategy of priority) {

            if (c[strategy]) {

                return {
                    preferredStrategy:
                        strategy,

                    preferredValue:
                        c[strategy],

                    candidates:
                        c
                };
            }
        }

        return {
            preferredStrategy:
                'INPUT',

            preferredValue:
                semanticInputName(
                    control
                ),

            candidates:
                c
        };
    }



    /* =========================================================
       VALUE
       ========================================================= */

    function elementValue(element) {

        if (!element) {
            return null;
        }

        const tag =
            (element.tagName || '')
                .toLowerCase();

        const type =
            clean(
                element.getAttribute
                    ?.('type')
            )?.toLowerCase();

        if (type === 'radio' ||
            type === 'checkbox') {

            if ('checked' in element &&
                !element.checked) {

                return null;
            }
        }

        if (tag === 'select') {

            const option =
                element.selectedOptions
                    ?.[0];

            return clean(
                element.value ||
                option?.textContent
            );
        }

        if (element.isContentEditable) {

            return clean(
                element.innerText
            );
        }

        if ('value' in element &&
            element.value != null) {

            const value =
                clean(element.value);

            if (value) {
                return value;
            }
        }

        return clean(
            element.getAttribute
                ?.('aria-valuetext') ||
            element.getAttribute
                ?.('aria-valuenow') ||
            element.getAttribute
                ?.('data-value') ||
            element.getAttribute
                ?.('ng-reflect-model')
        );
    }



    function currentValue(
        control) {

        if (!control) {
            return null;
        }

        const elements = [];

        const push = element => {

            if (element &&
                !elements.includes(element)) {

                elements.push(element);
            }
        };

        push(control.nativeElement);

        /*
         * For a logical custom Angular field, read the actual value from
         * its internal input/select/textarea instead of the text of an
         * implementation button such as "Open picker".
         */
        control.customHost
            ?.querySelectorAll
            ?.(
                'input:not([type="button"]):not([type="submit"]):not([type="reset"]),' +
                'textarea,select,[contenteditable="true"],' +
                '[role="textbox"],[role="combobox"],' +
                '[role="slider"],[role="spinbutton"]'
            )
            .forEach(push);

        for (const element of elements) {

            const value =
                elementValue(element);

            if (value) {
                return value;
            }
        }

        /*
         * Generic custom-component fallbacks. These are useful for
         * controls that render the selected value as text instead of
         * keeping a native visible input.
         */
        const host =
            control.customHost;

        if (host) {

            const hostValue =
                clean(
                    host.getAttribute?.('value') ||
                    host.getAttribute?.('data-value') ||
                    host.getAttribute?.('ng-reflect-model')
                );

            if (hostValue) {
                return hostValue;
            }

            const selected =
                host.querySelector?.(
                    '[aria-selected="true"],' +
                    '[data-selected="true"],' +
                    '.selected-value,.selection-value,' +
                    '[class*="selected-value"],' +
                    '[class*="selection-value"]'
                );

            const selectedText =
                clean(
                    selected?.innerText ||
                    selected?.textContent
                );

            if (selectedText) {
                return selectedText;
            }
        }

        return null;
    }



    function logicalKey(control) {
        return control?.customHost ||
               control?.nativeElement ||
               null;
    }



    function logicalAction(control) {

        const type =
            detectInputType(control);

        if (type === 'DATE') {
            return 'DATE_INPUT';
        }

        if (type === 'AUTOCOMPLETE' ||
            type === 'SEARCH' ||
            type === 'SELECT' ||
            type === 'OPTION') {

            return 'SELECT';
        }

        return 'INPUT';
    }



    function emitLogicalValue(
        control,
        value,
        actionType = null) {

        const actual =
            clean(value);

        if (!control ||
            !actual) {

            return false;
        }

        const key =
            logicalKey(control);

        if (key) {

            const previous =
                lastLogicalEmissions.get(key);

            if (previous &&
                previous.value === actual &&
                Date.now() - previous.time < 1000) {

                return false;
            }

            lastLogicalEmissions.set(
                key,
                {
                    value: actual,
                    time: Date.now()
                }
            );
        }

        emit({
            ...baseEvent(
                control,
                actionType ||
                    logicalAction(control)
            ),

            value: {
                raw: actual
            }
        });

        return true;
    }



    function waitForLogicalValueChange(
        control,
        beforeValue,
        attempt = 0) {

        if (!control ||
            attempt > 12) {

            return;
        }

        setTimeout(
            () => {

                const actual =
                    currentValue(control);

                if (actual &&
                    actual !== beforeValue) {

                    const state =
                        selectionState(control);

                    const action =
                        logicalAction(control);

                    if (action === 'SELECT') {

                        emitSelection(
                            control,
                            actual,
                            actual,
                            actual,
                            state?.searchText
                                ? 'SEARCH_AND_SELECT'
                                : 'SELECT'
                        );

                    } else {

                        emitLogicalValue(
                            control,
                            actual,
                            action
                        );
                    }

                    return;
                }

                waitForLogicalValueChange(
                    control,
                    beforeValue,
                    attempt + 1
                );
            },
            attempt < 3 ? 75 : 150
        );
    }



    function checkedState(
        control) {

        const element =
            control.nativeElement;

        if (!element) {
            return null;
        }

        if ('checked' in element) {

            return !!element.checked;
        }

        const aria =
            clean(
                element
                    .getAttribute
                    ?.('aria-checked')
            );

        if (aria === 'true') {
            return true;
        }

        if (aria === 'false') {
            return false;
        }

        return null;
    }




    function selectionState(control) {

        const key =
            logicalKey(control);

        if (!key) {
            return null;
        }

        let state =
            selectionStates.get(key);

        if (!state) {

            state = {
                control,
                searchText: null,
                selectedValues: new Set(),
                selectedTexts: new Set()
            };

            selectionStates.set(
                key,
                state
            );
        }

        state.control = control;

        return state;
    }



    function isSearchEntryElement(element) {

        if (!element) {
            return false;
        }

        const type =
            clean(
                element.getAttribute
                    ?.('type')
            )?.toLowerCase();

        const role =
            clean(
                element.getAttribute
                    ?.('role')
            )?.toLowerCase();

        return (
            type === 'search' ||
            role === 'searchbox' ||
            element.getAttribute
                ?.('aria-autocomplete') != null
        );
    }



    function isOverlayElement(element) {

        if (!element) {
            return false;
        }

        return !!(
            element.closest
                ?.(
                    '[role="listbox"],' +
                    '[role="grid"],' +
                    '[role="menu"],' +
                    '[role="tree"],' +
                    '.cdk-overlay-pane,' +
                    '.cdk-overlay-container,' +
                    '[class*="overlay"],' +
                    '[class*="dropdown"],' +
                    '[class*="popup"]'
                )
        );
    }



    function optionText(element) {

        if (!element) {
            return null;
        }

        return clean(
            element.getAttribute
                ?.('aria-label') ||
            element.getAttribute
                ?.('data-label') ||
            shortText(element)
        );
    }



    function optionValue(element) {

        if (!element) {
            return null;
        }

        return clean(
            element.getAttribute
                ?.('value') ||
            element.getAttribute
                ?.('data-value') ||
            element.getAttribute
                ?.('ng-reflect-value') ||
            optionText(element)
        );
    }



    function emitSelection(
        control,
        rawValue,
        selectedValue,
        selectedText,
        actionType) {

        const actual =
            clean(rawValue) ||
            clean(selectedValue) ||
            clean(selectedText);

        if (!control ||
            !actual) {

            return false;
        }

        const key =
            logicalKey(control);

        if (key) {

            const previous =
                lastLogicalEmissions.get(key);

            if (previous &&
                previous.value === actual &&
                previous.actionType === actionType &&
                Date.now() - previous.time < 1200) {

                return false;
            }

            lastLogicalEmissions.set(
                key,
                {
                    value: actual,
                    actionType,
                    time: Date.now()
                }
            );
        }

        const state =
            selectionState(control);

        emit({
            ...baseEvent(
                control,
                actionType ||
                    'SELECT'
            ),

            value: {
                raw: actual,
                searchText:
                    state?.searchText || null,
                selectedValue:
                    clean(selectedValue) || actual,
                selectedText:
                    clean(selectedText) || actual
            }
        });

        if (state) {
            state.searchText = null;
        }

        return true;
    }



    function scheduleMultiSelectEmit(
        control) {

        const key =
            logicalKey(control);

        if (!key) {
            return;
        }

        const oldTimer =
            multiSelectTimers.get(key);

        if (oldTimer) {
            clearTimeout(oldTimer);
        }

        const timer =
            setTimeout(
                () => {

                    const state =
                        selectionStates.get(key);

                    if (!state) {
                        return;
                    }

                    const values =
                        Array.from(
                            state.selectedValues
                        );

                    const texts =
                        Array.from(
                            state.selectedTexts
                        );

                    if (!values.length &&
                        !texts.length) {

                        return;
                    }

                    emitSelection(
                        state.control,
                        values.join(' | ') ||
                            texts.join(' | '),
                        values.join(' | ') || null,
                        texts.join(' | ') || null,
                        'MULTI_SELECT'
                    );

                    multiSelectTimers.delete(key);
                },
                350
            );

        multiSelectTimers.set(
            key,
            timer
        );
    }



    function recordOverlayCheckbox(
        element) {

        if (!activeLogicalControl ||
            !element) {

            return false;
        }

        const state =
            selectionState(
                activeLogicalControl
            );

        if (!state) {
            return false;
        }

        const checked =
            'checked' in element
                ? !!element.checked
                : element.getAttribute
                    ?.('aria-checked') === 'true';

        const container =
            element.closest
                ?.(
                    '[role="option"],' +
                    '[role="menuitemcheckbox"],' +
                    'label,li,[class*="option"],[class*="item"]'
                ) ||
            element;

        const value =
            optionValue(container);

        const text =
            optionText(container);

        if (checked) {

            if (value) {
                state.selectedValues.add(value);
            }

            if (text) {
                state.selectedTexts.add(text);
            }

        } else {

            if (value) {
                state.selectedValues.delete(value);
            }

            if (text) {
                state.selectedTexts.delete(text);
            }
        }

        scheduleMultiSelectEmit(
            activeLogicalControl
        );

        return true;
    }



    /* =========================================================
       DIALOG CONTEXT
       ========================================================= */

    function dialogContext(
        control) {

        const element =
            control.nativeElement;

        const dialog =
            element
                ?.closest
                ?.(`
                    [role="dialog"],
                    [aria-modal="true"]
                `);

        if (!dialog) {
            return null;
        }

        return {
            type:
                'DIALOG',

            name:
                clean(
                    dialog
                        .getAttribute(
                            'aria-label'
                        ) ||

                    labelledBy(
                        dialog
                    ) ||

                    dialog
                        .querySelector(
                            'h1,h2,h3,[role="heading"]'
                        )
                        ?.textContent
                ) ||
                'dialog'
        };
    }



    /* =========================================================
       EVENT EMIT
       ========================================================= */

    function emit(
        event) {

        const now =
            Date.now();

        const full = {

            eventId:
                `${now}-${++eventCounter}`,

            timestamp:
                now,

            stage:
                currentStage,

            url:
                location.href,

            ...event
        };

        window
            .__angularRecorderEvents
            .push(full);

        console.log(
            PREFIX +
            JSON.stringify(full)
        );
    }



    function baseEvent(
        control,
        actionType,
        typeOverride) {

        return {

            actionType,

            inputType:
                typeOverride ||
                detectInputType(
                    control
                ),

            input:
                semanticInputName(
                    control
                ),

            identifyBy:
                identifyBy(
                    control
                ),

            context:
                dialogContext(
                    control
                )
        };
    }



    /* =========================================================
       INPUT NORMALIZATION
       ========================================================= */

    function editableAction(
        control) {

        const type =
            detectInputType(
                control
            );

        if (
            type ===
            'AUTOCOMPLETE' ||
            type ===
            'SEARCH'
        ) {

            return 'SEARCH';
        }

        if (
            type ===
            'DATE'
        ) {

            return 'DATE_INPUT';
        }

        return 'INPUT';
    }



    function scheduleEditable(
        control) {

        const element =
            control.nativeElement;

        if (!element) {
            return;
        }

        const oldTimer =
            timers.get(
                element
            );

        if (oldTimer) {

            clearTimeout(
                oldTimer
            );
        }

        pending.set(
            element,
            {
                control,
                actionType:
                    editableAction(
                        control
                    )
            }
        );

        /*
         * Capture final typed value,
         * not one event for every character.
         */
        const timer =
            setTimeout(
                () => {

                    const item =
                        pending.get(
                            element
                        );

                    if (!item) {
                        return;
                    }

                    emitLogicalValue(
                        item.control,
                        currentValue(
                            item.control
                        ),
                        item.actionType
                    );

                    pending.delete(
                        element
                    );

                    timers.delete(
                        element
                    );

                },
                500
            );

        timers.set(
            element,
            timer
        );
    }



    function flushEditable(
        control) {

        const element =
            control.nativeElement;

        if (!element ||
            !pending.has(
                element
            )) {

            return false;
        }

        const oldTimer =
            timers.get(
                element
            );

        if (oldTimer) {

            clearTimeout(
                oldTimer
            );
        }

        const item =
            pending.get(
                element
            );

        emitLogicalValue(
            item.control,
            currentValue(
                item.control
            ),
            item.actionType
        );

        pending.delete(
            element
        );

        timers.delete(
            element
        );

        return true;
    }



    /* =========================================================
       ACTIVE LOGICAL CONTROL
       ========================================================= */

    document.addEventListener(
        'focusin',
        event => {

            const control =
                resolveControl(event);

            if (control?.customHost &&
                hasControlBinding(
                    control.customHost
                )) {

                activeLogicalControl =
                    control;
            }
        },
        true
    );



    /* =========================================================
       INPUT EVENT
       ========================================================= */

    document.addEventListener(
        'input',
        event => {

            const control =
                resolveControl(
                    event
                );

            const type =
                detectInputType(
                    control
                );

            /*
             * Internal search box of a custom dropdown/autocomplete:
             * keep the typed search text as metadata, but do not emit
             * a separate SEARCH event. The final option selection will
             * produce the logical field event.
             */
            if (control.customHost &&
                hasControlBinding(control.customHost) &&
                isSearchEntryElement(
                    control.clickedElement ||
                    control.nativeElement
                )) {

                activeLogicalControl =
                    control;

                const state =
                    selectionState(control);

                if (state) {
                    state.searchText =
                        currentValue({
                            ...control,
                            customHost: null,
                            nativeElement:
                                control.clickedElement ||
                                control.nativeElement
                        });
                }

                return;
            }

            if (
                type ===
                'CHECKBOX' ||

                type ===
                'RADIO' ||

                type ===
                'TOGGLE' ||

                type ===
                'FILE' ||

                type ===
                'SELECT'
            ) {

                return;
            }

            scheduleEditable(
                control
            );
        },
        true
    );



    /* =========================================================
       CHANGE EVENT
       ========================================================= */

    document.addEventListener(
        'change',
        event => {

            const control =
                resolveControl(
                    event
                );

            const flushed =
                flushEditable(
                    control
                );

            const element =
                control.nativeElement;

            const type =
                detectInputType(
                    control
                );


            /*
             * Checkbox inside a popup/list can represent one item of a
             * multi-select field. Associate it with the active logical
             * custom control rather than creating a separate form field.
             */
            if (activeLogicalControl &&
                isOverlayElement(
                    control.clickedElement ||
                    element
                ) &&
                (
                    element?.getAttribute?.('type') === 'checkbox' ||
                    element?.getAttribute?.('role') === 'checkbox' ||
                    element?.getAttribute?.('role') === 'menuitemcheckbox'
                )) {

                if (recordOverlayCheckbox(element)) {
                    return;
                }
            }



            if (type === 'FILE') {

                const files =
                    element.files
                        ? Array.from(
                            element.files
                        )
                        : [];

                emit({
                    ...baseEvent(
                        control,
                        'FILE_UPLOAD',
                        'FILE'
                    ),

                    value: {
                        fileName:
                            files.length
                                ? files
                                    .map(
                                        f =>
                                            f.name
                                    )
                                    .join(', ')
                                : null
                    }
                });

                return;
            }



            if (
                type ===
                'CHECKBOX' ||
                type ===
                'TOGGLE'
            ) {

                const checked =
                    checkedState(
                        control
                    );

                emit({
                    ...baseEvent(
                        control,
                        checked
                            ? 'CHECK'
                            : 'UNCHECK',
                        type
                    ),

                    value: {
                        raw:
                            currentValue(
                                control
                            ),

                        checked
                    }
                });

                return;
            }



            if (type === 'RADIO') {

                const checked =
                    checkedState(
                        control
                    );

                if (checked === false) {
                    return;
                }

                emit({
                    ...baseEvent(
                        control,
                        'RADIO',
                        'RADIO'
                    ),

                    value: {
                        raw:
                            currentValue(
                                control
                            ),

                        selectedValue:
                            currentValue(
                                control
                            ),

                        selectedText:
                            semanticInputName(
                                control
                            ),

                        checked:
                            true
                    }
                });

                return;
            }



            if (
                element &&
                element
                    .tagName
                    ?.toLowerCase() ===
                'select'
            ) {

                const option =
                    element
                        .selectedOptions
                        ?.[0];

                emit({
                    ...baseEvent(
                        control,
                        'SELECT',
                        'SELECT'
                    ),

                    value: {
                        raw:
                            clean(
                                element.value
                            ),

                        selectedValue:
                            clean(
                                element.value
                            ),

                        selectedText:
                            clean(
                                option
                                    ?.textContent
                            )
                    }
                });

                return;
            }



            /*
             * If the pending input was just flushed above, it has already
             * emitted the final value. Do not emit the same sequence again.
             */
            if (flushed) {
                return;
            }

            emitLogicalValue(
                control,
                currentValue(control),
                editableAction(control)
            );
        },
        true
    );



    /* =========================================================
       BLUR
       ========================================================= */

    document.addEventListener(
        'focusout',
        event => {

            const control =
                resolveControl(
                    event
                );

            flushEditable(
                control
            );
        },
        true
    );



    /* =========================================================
       CLICK / CUSTOM COMPONENT / OVERLAY
       ========================================================= */

    document.addEventListener(
        'click',
        event => {

            const control =
                resolveControl(event);

            const element =
                control.nativeElement;

            const clicked =
                control.clickedElement ||
                element;

            if (!element) {
                return;
            }

            const type =
                detectInputType(control);

            const role =
                clean(
                    clicked
                        ?.getAttribute
                        ?.('role')
                );

            /*
             * A bound custom Angular component is ONE logical input.
             * Internal buttons/icons/calendar cells are implementation
             * details and must not become separate CLICK events.
             */
            if (control.customHost &&
                hasControlBinding(
                    control.customHost
                )) {

                const before =
                    currentValue(control);

                activeLogicalControl =
                    control;

                /*
                 * Editable controls are captured by input/change. For
                 * trigger/option/calendar clicks, wait until Angular has
                 * written the completed value back into the logical field.
                 */
                if (
                    type !== 'TEXT' &&
                    type !== 'PASSWORD' &&
                    type !== 'EMAIL' &&
                    type !== 'NUMBER' &&
                    type !== 'TEL' &&
                    type !== 'URL' &&
                    type !== 'SEARCH' &&
                    type !== 'TEXTAREA'
                ) {

                    waitForLogicalValueChange(
                        control,
                        before
                    );
                }

                return;
            }

            /*
             * Overlay content can be rendered outside the bound component
             * (Angular CDK/Material and many custom libraries do this).
             * Associate the overlay selection with the last active logical
             * form control and read the resulting VALUE from that field.
             */
            if (activeLogicalControl &&
                (
                    role === 'option' ||
                    role === 'gridcell' ||
                    role === 'menuitem' ||
                    role === 'treeitem' ||
                    role === 'radio' ||
                    clicked?.getAttribute
                        ?.('aria-selected') != null ||
                    isOverlayElement(clicked)
                )) {

                /*
                 * Checkbox options are handled by their change event so
                 * multiple selections can be accumulated into one value.
                 */
                const clickedCheckbox =
                    clicked.matches
                        ?.('input[type="checkbox"],[role="checkbox"],[role="menuitemcheckbox"]') ||
                    clicked.querySelector
                        ?.('input[type="checkbox"],[role="checkbox"],[role="menuitemcheckbox"]');

                if (clickedCheckbox) {
                    return;
                }

                const before =
                    currentValue(
                        activeLogicalControl
                    );

                const selectedText =
                    optionText(clicked);

                const selectedValue =
                    optionValue(clicked);

                const state =
                    selectionState(
                        activeLogicalControl
                    );

                /*
                 * Direct select and search-then-select are both valid.
                 * Capture the clicked option immediately, then also watch
                 * the logical control for the framework-updated value.
                 */
                setTimeout(
                    () => {

                        const actual =
                            currentValue(
                                activeLogicalControl
                            );

                        emitSelection(
                            activeLogicalControl,
                            actual ||
                                selectedValue ||
                                selectedText,
                            selectedValue ||
                                actual,
                            selectedText ||
                                actual,
                            state?.searchText
                                ? 'SEARCH_AND_SELECT'
                                : 'SELECT'
                        );
                    },
                    100
                );

                waitForLogicalValueChange(
                    activeLogicalControl,
                    before
                );

                return;
            }

            /* Input focus is not a separate action. */
            if (
                type === 'TEXT' ||
                type === 'PASSWORD' ||
                type === 'EMAIL' ||
                type === 'NUMBER' ||
                type === 'TEL' ||
                type === 'URL' ||
                type === 'SEARCH' ||
                type === 'TEXTAREA' ||
                type === 'AUTOCOMPLETE'
            ) {

                return;
            }

            /* change captures these */
            if (
                type === 'CHECKBOX' ||
                type === 'RADIO' ||
                type === 'TOGGLE'
            ) {

                return;
            }

            /* Standalone option with no logical bound control. */
            if (role === 'option' ||
                type === 'OPTION') {

                const selectedText =
                    shortText(clicked);

                emit({
                    ...baseEvent(
                        control,
                        'SELECT',
                        'OPTION'
                    ),

                    value: {
                        raw: selectedText,
                        selectedValue:
                            clean(
                                clicked
                                    ?.getAttribute
                                    ?.('value')
                            ) ||
                            selectedText,
                        selectedText
                    }
                });

                return;
            }

            const hasPopup =
                clean(
                    clicked
                        ?.getAttribute
                        ?.('aria-haspopup')
                );

            const controls =
                clean(
                    clicked
                        ?.getAttribute
                        ?.('aria-controls')
                );

            emit({
                ...baseEvent(
                    control,
                    hasPopup || controls
                        ? 'OPEN_POPUP'
                        : 'CLICK'
                ),

                value: {
                    raw:
                        shortText(clicked) ||
                        semanticInputName(control)
                }
            });
        },
        true
    );



    /* =========================================================
       KEYBOARD
       ========================================================= */

    document.addEventListener(
        'keydown',
        event => {

            if (
                ![
                    'Enter',
                    'Tab',
                    'Escape'
                ]
                .includes(
                    event.key
                )
            ) {

                return;
            }

            const control =
                resolveControl(
                    event
                );

            emit({
                ...baseEvent(
                    control,
                    'KEY'
                ),

                value: {
                    raw:
                        event.key
                }
            });
        },
        true
    );



    /* =========================================================
       ANGULAR SPA STAGE TRACKING
       ========================================================= */

    function checkStageChange() {

        setTimeout(
            () => {

                if (
                    location.href !==
                    lastUrl
                ) {

                    lastUrl =
                        location.href;

                    currentStage++;
                }

            },
            0
        );
    }



    const originalPushState =
        history.pushState
            .bind(history);

    history.pushState =
        function(...args) {

            const result =
                originalPushState(
                    ...args
                );

            checkStageChange();

            return result;
        };



    const originalReplaceState =
        history.replaceState
            .bind(history);

    history.replaceState =
        function(...args) {

            const result =
                originalReplaceState(
                    ...args
                );

            checkStageChange();

            return result;
        };



    window.addEventListener(
        'popstate',
        checkStageChange,
        true
    );



    /* =========================================================
       DYNAMIC ANGULAR / OVERLAY DOM
       ========================================================= */

    const observer =
        new MutationObserver(
            () => {

                lastMutationAt =
                    Date.now();
            }
        );

    observer.observe(
        document.documentElement,
        {
            childList:
                true,

            subtree:
                true
        }
    );

})();
""";
}