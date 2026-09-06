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

        for (JsonNode node : rawEvents) {

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



    function findLogicalAngularHost(
        nativeElement,
        event) {

        const nodes = [
            ...eventPath(event),
            ...ancestors(nativeElement)
        ];

        for (const node of nodes) {

            if (node ===
                nativeElement) {

                continue;
            }

            if (isLogicalAngularHost(
                node)) {

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

    function currentValue(
        control) {

        const element =
            control.nativeElement;

        if (!element) {
            return null;
        }

        if (
            element
                .isContentEditable
        ) {

            return clean(
                element.innerText
            );
        }

        if (
            'value' in element &&
            element.value != null
        ) {

            return clean(
                element.value
            );
        }

        return clean(
            element
                .getAttribute
                ?.('aria-valuetext') ||

            element
                .getAttribute
                ?.('aria-valuenow')
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

        const full = {

            timestamp:
                Date.now(),

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

                    emit({
                        ...baseEvent(
                            item.control,
                            item.actionType
                        ),

                        value: {
                            raw:
                                currentValue(
                                    item.control
                                )
                        }
                    });

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

        const item =
            pending.get(
                element
            );

        emit({
            ...baseEvent(
                item.control,
                item.actionType
            ),

            value: {
                raw:
                    currentValue(
                        item.control
                    )
            }
        });

        pending.delete(
            element
        );

        timers.delete(
            element
        );
    }



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

            flushEditable(
                control
            );

            const element =
                control.nativeElement;

            const type =
                detectInputType(
                    control
                );



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



            emit({
                ...baseEvent(
                    control,
                    editableAction(
                        control
                    )
                ),

                value: {
                    raw:
                        currentValue(
                            control
                        )
                }
            });
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
       CLICK / OPTION / POPUP
       ========================================================= */

    document.addEventListener(
        'click',
        event => {

            const control =
                resolveControl(
                    event
                );

            const element =
                control.nativeElement;

            if (!element) {
                return;
            }

            const type =
                detectInputType(
                    control
                );

            const role =
                clean(
                    element
                        .getAttribute
                        ?.('role')
                );



            /*
             * Input focus/placeholder click
             * is NOT a separate event.
             */
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



            /*
             * Change event will capture these.
             */
            if (
                type === 'CHECKBOX' ||
                type === 'RADIO' ||
                type === 'TOGGLE'
            ) {

                return;
            }



            /*
             * Generic overlay/list option.
             */
            if (
                role === 'option' ||
                type === 'OPTION'
            ) {

                const selectedText =
                    shortText(
                        element
                    );

                emit({
                    ...baseEvent(
                        control,
                        'SELECT',
                        'OPTION'
                    ),

                    value: {

                        raw:
                            selectedText,

                        selectedValue:
                            clean(
                                element
                                    .getAttribute
                                    ?.('value')
                            ) ||
                            selectedText,

                        selectedText:
                            selectedText
                    },

                    wait: {
                        type:
                            'OPTION_VISIBLE',

                        target:
                            selectedText,

                        role:
                            'option'
                    }
                });

                return;
            }



            const hasPopup =
                clean(
                    element
                        .getAttribute
                        ?.('aria-haspopup')
                );

            const controls =
                clean(
                    element
                        .getAttribute
                        ?.('aria-controls')
                );



            emit({
                ...baseEvent(
                    control,
                    hasPopup ||
                    controls
                        ? 'OPEN_POPUP'
                        : 'CLICK'
                ),

                value: {
                    raw:
                        shortText(
                            element
                        ) ||
                        semanticInputName(
                            control
                        )
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