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

    const PREFIX = '__ANGULAR_RECORDER_EVENT__';

    /*
     * One logical state per form input / custom component.
     *
     * Important:
     * the key is the complete logical input container,
     * not the internal HTML input.
     */
    const controlStates = new WeakMap();

    let activeLogicalControl = null;
    let currentStage = 1;
    let lastUrl = location.href;


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


    function eventPath(event) {

        if (!event?.composedPath) {
            return [];
        }

        return event
            .composedPath()
            .filter(
                node =>
                    node &&
                    node.nodeType === Node.ELEMENT_NODE
            );
    }


    function eventElement(event) {

        const path =
            eventPath(event);

        if (path.length) {
            return path[0];
        }

        return event?.target || null;
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


    /* =========================================================
       CUSTOM ANGULAR CONTROL / DIRECTIVE DETECTION
       ========================================================= */

    function isCustomAngularHost(element) {

        if (!element ||
            !element.getAttribute) {

            return false;
        }

        const tag =
            (
                element.tagName || ''
            ).toLowerCase();

        /*
         * Generic custom Angular/Web component:
         *
         * mt-dropdown
         * dcrm-dcrm-datepicker
         * company-control
         * abc-selector
         *
         * No component name is hardcoded.
         */
        if (tag.includes('-')) {
            return true;
        }

        return (
            element.hasAttribute('formControlName') ||
            element.hasAttribute('formcontrolname') ||
            element.hasAttribute('controlName') ||
            element.hasAttribute('controlname') ||
            element.hasAttribute('ngModel') ||
            element.hasAttribute('ngmodel')
        );
    }


    function findCustomHost(element) {

        let current =
            element;

        while (
            current &&
            current !== document.body
        ) {

            if (isCustomAngularHost(current)) {
                return current;
            }

            current =
                current.parentElement;
        }

        return null;
    }


    /* =========================================================
       FORM GROUP / ONE DIV = ONE INPUT
       ========================================================= */

    function findFormGroup(element) {

        if (!element?.closest) {
            return null;
        }

        return element.closest(`
            .lmn-form-group,
            .form-group,
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


    function isEditableElement(element) {

        if (!element?.matches) {
            return false;
        }

        return element.matches(`
            input:not([type="hidden"]),
            textarea,
            select,
            [contenteditable="true"],
            [role="textbox"],
            [role="searchbox"],
            [role="combobox"],
            [role="slider"],
            [role="spinbutton"]
        `);
    }


    function findNativeInput(
        root,
        original
    ) {

        if (isEditableElement(original)) {
            return original;
        }

        if (!root?.querySelector) {
            return original;
        }

        return root.querySelector(`
            input:not([type="hidden"]),
            textarea,
            select,
            [contenteditable="true"],
            [role="textbox"],
            [role="searchbox"],
            [role="combobox"],
            [role="slider"],
            [role="spinbutton"]
        `) || original;
    }


    function resolveLogicalControl(element) {

        if (!element) {
            return null;
        }

        const host =
            findCustomHost(element);

        const group =
            findFormGroup(
                host || element
            );

        /*
         * Requirement:
         *
         * one div / form-group represents one logical input.
         */
        const root =
            group ||
            host ||
            element;

        const nativeElement =
            findNativeInput(
                root,
                element
            );

        return {
            root,
            group,
            host,
            nativeElement
        };
    }


    /* =========================================================
       LABEL DETECTION
       ========================================================= */

    function cleanLabelText(label) {

        if (!label) {
            return null;
        }

        const clone =
            label.cloneNode(true);

        /*
         * Remove tooltip, help, icons and validation decoration.
         */
        clone
            .querySelectorAll(`
                [role="tooltip"],
                [class*="tooltip"],
                [class*="error"],
                [class*="helper"],
                mat-icon,
                svg,
                small
            `)
            .forEach(
                element =>
                    element.remove()
            );

        let text =
            clean(
                clone.textContent
            );

        if (!text) {
            return null;
        }

        /*
         * Remove required markers such as:
         *
         * * Customer
         */
        text =
            text.replace(
                /^\\s*\\*+\\s*/,
                ''
            );

        return clean(text);
    }


    function labelledBy(element) {

        if (!element?.getAttribute) {
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
                        document.getElementById(id)
                )
                .filter(Boolean)
                .map(
                    node =>
                        node.innerText ||
                        node.textContent
                )
                .join(' ')
        );
    }


    function associatedLabel(element) {

        if (!element) {
            return null;
        }

        /*
         * HTML labels property.
         */
        if (
            element.labels &&
            element.labels.length
        ) {

            for (const label of element.labels) {

                const text =
                    cleanLabelText(label);

                if (text) {
                    return text;
                }
            }
        }

        /*
         * label[for=id]
         */
        const id =
            clean(element.id);

        if (id) {

            try {

                const label =
                    document.querySelector(
                        `label[for="${CSS.escape(id)}"]`
                    );

                if (label) {

                    const text =
                        cleanLabelText(label);

                    if (text) {
                        return text;
                    }
                }

            } catch (_) {
            }
        }

        /*
         * wrapping label
         */
        const wrapping =
            element.closest?.('label');

        if (wrapping) {

            const text =
                cleanLabelText(wrapping);

            if (text) {
                return text;
            }
        }

        return labelledBy(element);
    }


    function labelFromFormGroup(control) {

        const group =
            control?.group;

        if (!group) {
            return null;
        }

        const labels =
            Array.from(
                group.querySelectorAll('label')
            );

        for (const label of labels) {

            const text =
                cleanLabelText(label);

            if (text) {
                return text;
            }
        }

        return null;
    }


    function labelFor(control) {

        if (!control) {
            return null;
        }

        return clean(
            associatedLabel(
                control.nativeElement
            ) ||

            control.nativeElement
                ?.getAttribute
                ?.('aria-label') ||

            labelledBy(
                control.nativeElement
            ) ||

            control.host
                ?.getAttribute
                ?.('aria-label') ||

            labelledBy(
                control.host
            ) ||

            labelFromFormGroup(
                control
            )
        );
    }


    /* =========================================================
       CONTROL ATTRIBUTES
       ========================================================= */

    function attributeFromControl(
        control,
        ...names
    ) {

        const elements = [
            control?.nativeElement,
            control?.host
        ];

        for (const element of elements) {

            if (!element?.getAttribute) {
                continue;
            }

            for (const name of names) {

                const value =
                    clean(
                        element.getAttribute(name)
                    );

                if (value) {
                    return value;
                }
            }
        }

        return null;
    }


    function formControlName(control) {

        return attributeFromControl(
            control,
            'formControlName',
            'formcontrolname'
        );
    }


    function customControlName(control) {

        return attributeFromControl(
            control,
            'controlName',
            'controlname'
        );
    }


    /* =========================================================
       INPUT TYPE DETECTION
       ========================================================= */

    function detectInputType(control) {

        const element =
            control?.nativeElement;

        const root =
            control?.root;

        const type =
            clean(
                element
                    ?.getAttribute
                    ?.('type')
            )?.toLowerCase();

        const role =
            clean(
                element
                    ?.getAttribute
                    ?.('role') ||

                control?.host
                    ?.getAttribute
                    ?.('role')
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


        if (
            element
                ?.tagName
                ?.toLowerCase() ===
            'textarea'
        ) {

            return 'TEXTAREA';
        }


        if (
            element
                ?.tagName
                ?.toLowerCase() ===
            'select'
        ) {

            return 'SELECT';
        }


        /*
         * Search/autocomplete can be either the native control itself
         * or an internal element of a custom Angular component.
         */
        if (
            role === 'combobox' ||
            role === 'searchbox' ||
            element
                ?.getAttribute
                ?.('aria-autocomplete') ||

            root
                ?.querySelector
                ?.(`
                    [role="combobox"],
                    [role="searchbox"],
                    input[type="search"],
                    input[aria-autocomplete]
                `)
        ) {

            return 'AUTOCOMPLETE';
        }


        /*
         * Generic custom file component.
         */
        if (
            root
                ?.querySelector
                ?.('input[type="file"]')
        ) {

            return 'FILE';
        }


        /*
         * Generic custom checkbox.
         */
        if (
            root
                ?.querySelector
                ?.('input[type="checkbox"],[role="checkbox"]')
        ) {

            return 'CHECKBOX';
        }


        /*
         * Generic custom radio.
         */
        if (
            root
                ?.querySelector
                ?.('input[type="radio"],[role="radio"]')
        ) {

            return 'RADIO';
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
            return 'AUTOCOMPLETE';
        }


        if (
            element
                ?.isContentEditable
        ) {

            return 'RICH_TEXT';
        }


        return 'TEXT';
    }


    /* =========================================================
       IDENTIFICATION
       ========================================================= */

    function candidates(control) {

        const result = {};

        const label =
            labelFor(control);

        if (label) {
            result.LABEL = label;
        }


        const ariaLabel =
            attributeFromControl(
                control,
                'aria-label'
            );

        if (ariaLabel) {
            result.ARIA_LABEL = ariaLabel;
        }


        const fc =
            formControlName(control);

        if (fc) {
            result.FORM_CONTROL_NAME = fc;
        }


        const cn =
            customControlName(control);

        if (cn) {
            result.CONTROL_NAME = cn;
        }


        const name =
            attributeFromControl(
                control,
                'name'
            );

        if (name) {
            result.NAME = name;
        }


        const id =
            clean(
                control?.nativeElement?.id ||
                control?.host?.id
            );

        if (id) {
            result.ID = id;
        }


        const role =
            attributeFromControl(
                control,
                'role'
            );

        if (role) {
            result.ROLE = role;
        }


        /*
         * Placeholder is metadata only.
         *
         * It must never create a separate event.
         */
        const placeholder =
            attributeFromControl(
                control,
                'placeholder'
            );

        if (placeholder) {
            result.PLACEHOLDER = placeholder;
        }


        const testId =
            attributeFromControl(
                control,
                'data-testid',
                'data-test',
                'data-cy'
            );

        if (testId) {
            result.DATA_TESTID = testId;
        }


        if (control?.host) {

            const component =
                clean(
                    control.host
                        .tagName
                )?.toLowerCase();

            if (component) {
                result.COMPONENT = component;
            }
        }


        return result;
    }


    function inputName(control) {

        const c =
            candidates(control);

        /*
         * User requirement:
         *
         * label / logical input identity is primary.
         */
        return clean(
            c.LABEL ||
            c.ARIA_LABEL ||
            c.FORM_CONTROL_NAME ||
            c.CONTROL_NAME ||
            c.NAME ||
            c.ID ||
            c.PLACEHOLDER ||
            c.COMPONENT
        ) || 'unnamed';
    }


    function identifyBy(control) {

        const c =
            candidates(control);

        const priority = [
            'LABEL',
            'ARIA_LABEL',
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
                inputName(control),

            candidates:
                c
        };
    }


    /* =========================================================
       VALUE READING
       ========================================================= */

    function readElementValue(element) {

        if (!element) {
            return null;
        }


        if (element.isContentEditable) {

            const text =
                clean(
                    element.innerText
                );

            if (text) {
                return text;
            }
        }


        if (
            'value' in element &&
            element.value != null
        ) {

            const value =
                clean(
                    element.value
                );

            if (value) {
                return value;
            }
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


    function readValue(control) {

        if (!control) {
            return null;
        }

        const elements = [];

        /*
         * Prefer actual event/native input first.
         */
        if (control.nativeElement) {
            elements.push(
                control.nativeElement
            );
        }


        /*
         * Then inspect all internal editable controls.
         *
         * Important for custom components.
         */
        control.root
            ?.querySelectorAll
            ?.(`
                input:not([type="hidden"]),
                textarea,
                select,
                [contenteditable="true"],
                [role="textbox"],
                [role="searchbox"],
                [role="combobox"]
            `)
            .forEach(
                element => {

                    if (!elements.includes(element)) {

                        elements.push(element);
                    }
                }
            );


        for (const element of elements) {

            const value =
                readElementValue(
                    element
                );

            if (value) {
                return value;
            }
        }


        /*
         * Final fallback for custom selected-value display.
         */
        const selected =
            control.root
                ?.querySelector
                ?.(`
                    [aria-selected="true"],
                    [data-selected="true"],
                    .selected-value,
                    .selection-value,
                    [class*="selected-value"],
                    [class*="selection-value"]
                `);

        return clean(
            selected?.innerText ||
            selected?.textContent
        );
    }


    /* =========================================================
       DATE VALUE READING
       ========================================================= */

    function readDateValue(control) {

        if (!control) {
            return null;
        }

        const elements = [];


        if (control.nativeElement) {
            elements.push(
                control.nativeElement
            );
        }


        control.root
            ?.querySelectorAll
            ?.(`
                input:not([type="hidden"]),
                textarea,
                [role="textbox"],
                [contenteditable="true"]
            `)
            .forEach(
                element => {

                    if (!elements.includes(element)) {

                        elements.push(element);
                    }
                }
            );


        for (const element of elements) {

            const value =
                readElementValue(
                    element
                );

            if (value) {
                return value;
            }
        }

        return null;
    }


    function isDateControl(control) {

        if (!control) {
            return false;
        }


        if (
            detectInputType(control) ===
            'DATE'
        ) {

            return true;
        }


        return !!control.root
            ?.querySelector
            ?.(`
                input[type="date"],
                input[type="datetime-local"],
                input[type="month"],
                input[type="week"],
                input[type="time"]
            `);
    }


    /* =========================================================
       STATE
       ========================================================= */

    function getState(control) {

        if (!control?.root) {
            return null;
        }

        let state =
            controlStates.get(
                control.root
            );

        if (!state) {

            state = {

                control,

                searchText:
                    null,

                selectedText:
                    null,

                selectedValue:
                    null,

                finalValue:
                    null,

                previousValue:
                    readValue(control),

                dirty:
                    false,

                waitingForSelection:
                    false,

                dateWaitActive:
                    false
            };

            controlStates.set(
                control.root,
                state
            );
        }


        /*
         * Keep latest native element / host reference.
         */
        state.control =
            control;

        return state;
    }


    /* =========================================================
       FINAL EVENT ONLY
       ========================================================= */

    function emitFinal(
        state,
        actionType,
        inputType
    ) {

        if (!state?.control) {
            return;
        }

        const control =
            state.control;


        const value = {

            /*
             * raw = completed entered/selected value.
             */
            raw:
                state.finalValue,

            searchText:
                state.searchText,

            selectedValue:
                state.selectedValue,

            selectedText:
                state.selectedText
        };


        /*
         * Remove empty properties.
         */
        Object
            .keys(value)
            .forEach(
                key => {

                    if (
                        value[key] == null ||
                        value[key] === ''
                    ) {

                        delete value[key];
                    }
                }
            );


        const event = {

            timestamp:
                Date.now(),

            stage:
                currentStage,

            actionType,

            inputType:
                inputType ||
                detectInputType(control),

            input:
                inputName(control),

            identifyBy:
                identifyBy(control),

            value,

            url:
                location.href
        };


        window
            .__angularRecorderEvents
            .push(event);


        console.log(
            PREFIX +
            JSON.stringify(event)
        );


        controlStates.delete(
            control.root
        );


        if (
            activeLogicalControl ===
            control.root
        ) {

            activeLogicalControl =
                null;
        }
    }


    /* =========================================================
       DATE - WAIT UNTIL ACTUAL INPUT VALUE CHANGES
       ========================================================= */

    function waitForFinalDateValue(
        state,
        attempt = 0
    ) {

        if (!state) {
            return;
        }

        if (attempt === 0) {
            if (state.dateWaitActive) {
                return;
            }
            state.dateWaitActive = true;
        }


        /*
         * Up to roughly 2.5 seconds.
         */
        if (attempt > 25) {

            /*
             * Do not fall back to clicked calendar cell.
             *
             * If there is an existing actual value,
             * use only that.
             */
            const actual =
                readDateValue(
                    state.control
                );

            if (
                actual &&
                actual !== state.previousValue
            ) {

                state.finalValue =
                    actual;

                state.selectedText =
                    null;

                state.selectedValue =
                    null;

                state.dateWaitActive = false;

                emitFinal(
                    state,
                    'DATE_INPUT',
                    'DATE'
                );
                return;
            }

            state.dateWaitActive = false;
            return;
        }


        const actual =
            readDateValue(
                state.control
            );


        /*
         * The important rule:
         *
         * clicked calendar text could be "6".
         *
         * We only emit after the actual input becomes
         * "09/06/2026" or whatever format the application uses.
         */
        if (
            actual &&
            actual !== state.previousValue
        ) {

            state.finalValue =
                actual;

            /*
             * Calendar cell is not the final value.
             */
            state.selectedText =
                null;

            state.selectedValue =
                null;

            state.dateWaitActive = false;

            emitFinal(
                state,
                'DATE_INPUT',
                'DATE'
            );

            return;
        }


        setTimeout(
            () =>
                waitForFinalDateValue(
                    state,
                    attempt + 1
                ),
            100
        );
    }


    /* =========================================================
       FIND ACTIVE LOGICAL STATE FOR OVERLAY OPTION
       ========================================================= */

    function activeState() {

        if (!activeLogicalControl) {
            return null;
        }

        return controlStates.get(
            activeLogicalControl
        ) || null;
    }


    /* =========================================================
       FOCUS
       ========================================================= */

    document.addEventListener(
        'focusin',
        event => {

            const element =
                eventElement(event);

            const control =
                resolveLogicalControl(
                    element
                );

            if (!control) {
                return;
            }


            activeLogicalControl =
                control.root;


            getState(control);

            /*
             * NO EVENT.
             *
             * Opening/focusing alone is not user input.
             */
        },
        true
    );


    /* =========================================================
       INPUT
       ========================================================= */

    document.addEventListener(
        'input',
        event => {

            const element =
                eventElement(event);

            const control =
                resolveLogicalControl(
                    element
                );

            if (!control) {
                return;
            }


            activeLogicalControl =
                control.root;


            const state =
                getState(control);

            if (!state) {
                return;
            }


            const type =
                detectInputType(
                    control
                );


            const value =
                clean(
                    element?.value ??
                    readValue(control)
                );


            /*
             * SEARCH:
             *
             * store search value only.
             * Do NOT emit yet.
             */
            if (
                type ===
                'AUTOCOMPLETE' ||

                element
                    ?.getAttribute
                    ?.('role') ===
                'searchbox' ||

                element
                    ?.getAttribute
                    ?.('aria-autocomplete')
            ) {

                state.searchText =
                    value;

                state.dirty =
                    true;

                state.waitingForSelection =
                    true;

                return;
            }


            /*
             * DATE INPUT:
             * native and custom date controls may update asynchronously.
             */
            if (
                type === 'DATE' ||
                isDateControl(control)
            ) {
                state.finalValue = value;
                state.dirty = true;

                setTimeout(
                    () => waitForFinalDateValue(state),
                    0
                );

                return;
            }


            /*
             * NORMAL INPUT:
             *
             * Only keep latest entered value.
             */
            state.finalValue =
                value;

            state.dirty =
                true;
        },
        true
    );


    /* =========================================================
       CHANGE
       ========================================================= */

    document.addEventListener(
        'change',
        event => {

            const element =
                eventElement(event);

            const control =
                resolveLogicalControl(
                    element
                );

            if (!control) {
                return;
            }


            activeLogicalControl =
                control.root;


            const state =
                getState(control);

            if (!state) {
                return;
            }


            const type =
                detectInputType(
                    control
                );


            /* ---------------- FILE ---------------- */

            if (type === 'FILE') {

                const files =
                    element?.files
                        ? Array.from(
                            element.files
                        )
                        : [];


                state.finalValue =
                    files
                        .map(
                            file =>
                                file.name
                        )
                        .join(', ');


                state.selectedValue =
                    null;

                state.selectedText =
                    null;


                emitFinal(
                    state,
                    'FILE_UPLOAD',
                    'FILE'
                );

                return;
            }


            /* ---------------- CHECKBOX ---------------- */

            if (
                type === 'CHECKBOX' ||
                type === 'TOGGLE'
            ) {

                const checked =
                    'checked' in element
                        ? !!element.checked
                        : (
                            element
                                ?.getAttribute
                                ?.('aria-checked') ===
                            'true'
                        );


                state.finalValue =
                    String(checked);


                emitFinal(
                    state,
                    checked
                        ? 'CHECK'
                        : 'UNCHECK',
                    type
                );

                return;
            }


            /* ---------------- RADIO ---------------- */

            if (type === 'RADIO') {

                const checked =
                    'checked' in element
                        ? !!element.checked
                        : true;


                if (!checked) {
                    return;
                }


                state.finalValue =
                    clean(
                        element?.value
                    ) ||
                    shortText(element);


                state.selectedValue =
                    state.finalValue;


                state.selectedText =
                    shortText(element);


                emitFinal(
                    state,
                    'RADIO',
                    'RADIO'
                );

                return;
            }


            /* ---------------- NATIVE SELECT ---------------- */

            if (
                element
                    ?.tagName
                    ?.toLowerCase() ===
                'select'
            ) {

                const option =
                    element
                        ?.selectedOptions
                        ?.[0];


                state.selectedValue =
                    clean(
                        element.value
                    );


                state.selectedText =
                    clean(
                        option?.textContent
                    );


                state.finalValue =
                    state.selectedValue ||
                    state.selectedText;


                emitFinal(
                    state,
                    'SELECT',
                    'SELECT'
                );

                return;
            }


            /* ---------------- DATE ---------------- */

            if (
                type === 'DATE' ||
                isDateControl(control)
            ) {

                /*
                 * Date controls are special: many Angular/custom
                 * datepickers update the bound/native value after
                 * the change handler or without a useful focusout.
                 *
                 * Mark dirty and actively wait for the authoritative
                 * final value instead of relying on focusout alone.
                 */
                const actual =
                    readDateValue(control);

                if (actual) {
                    state.finalValue = actual;
                }

                state.dirty = true;

                setTimeout(
                    () => waitForFinalDateValue(state),
                    0
                );

                return;
            }


            /* ---------------- NORMAL INPUT ---------------- */

            const actual =
                readValue(control);


            if (actual) {

                state.finalValue =
                    actual;
            }


            state.dirty =
                true;
        },
        true
    );


    /* =========================================================
       CLICK
       ========================================================= */

    document.addEventListener(
        'click',
        event => {

            const element =
                eventElement(event);

            if (!element) {
                return;
            }


            const role =
                clean(
                    element
                        ?.getAttribute
                        ?.('role')
                )?.toLowerCase();


            /*
             * ==================================================
             * ACTIVE DATE PICKER
             * ==================================================
             *
             * Calendar cells in Angular/custom datepickers are often
             * buttons/gridcells and do not use role=option. Whenever a
             * date control is active, allow the click to complete and
             * then wait for the actual input value to change. Clicking
             * only the datepicker opener is harmless because
             * waitForFinalDateValue emits only when the real value
             * differs from previousValue.
             */
            const dateState = activeState();

            if (
                dateState &&
                isDateControl(dateState.control)
            ) {
                setTimeout(
                    () => waitForFinalDateValue(dateState),
                    0
                );
            }


            /*
             * ==================================================
             * OVERLAY OPTION
             * ==================================================
             *
             * It can be outside the form group.
             */
            if (
                role === 'option' ||
                element
                    ?.getAttribute
                    ?.('aria-selected') != null
            ) {

                const state =
                    activeState();

                if (!state) {
                    return;
                }


                /*
                 * DATE CONTROL SPECIAL HANDLING
                 *
                 * Never save clicked date-cell text.
                 *
                 * Example clicked:
                 *
                 * "6"
                 *
                 * Expected final:
                 *
                 * "09/06/2026"
                 */
                if (
                    isDateControl(
                        state.control
                    )
                ) {

                    waitForFinalDateValue(
                        state
                    );

                    return;
                }


                /*
                 * SEARCH / CUSTOM SELECT
                 */
                const selectedText =
                    shortText(
                        element
                    );


                state.selectedText =
                    selectedText;


                state.selectedValue =
                    clean(
                        element
                            ?.getAttribute
                            ?.('value')
                    ) ||
                    clean(
                        element
                            ?.getAttribute
                            ?.('data-value')
                    ) ||
                    selectedText;


                /*
                 * Angular/custom component may update its
                 * visible value after the click handler completes.
                 */
                setTimeout(
                    () => {

                        const actual =
                            readValue(
                                state.control
                            );


                        /*
                         * Prefer selected logical value.
                         *
                         * Do not use search box text as final value.
                         */
                        state.finalValue =
                            state.selectedValue ||
                            actual ||
                            state.selectedText;


                        emitFinal(
                            state,
                            state.searchText
                                ? 'SEARCH_AND_SELECT'
                                : 'SELECT',

                            state.searchText
                                ? 'AUTOCOMPLETE'
                                : detectInputType(
                                    state.control
                                )
                        );

                    },
                    100
                );

                return;
            }


            /*
             * ==================================================
             * NORMAL CONTROL CLICK
             * ==================================================
             */

            const control =
                resolveLogicalControl(
                    element
                );

            if (!control) {
                return;
            }


            activeLogicalControl =
                control.root;


            const state =
                getState(control);

            if (!state) {
                return;
            }


            /*
             * Store pre-interaction value for date picker.
             */
            if (
                isDateControl(control)
            ) {

                state.previousValue =
                    readDateValue(
                        control
                    );
            }


            /*
             * NO EVENT HERE.
             *
             * This intentionally suppresses:
             *
             * CLICK dropdown
             * CLICK placeholder
             * OPEN datepicker
             * OPEN autocomplete
             * focus input
             *
             * We wait for the completed value.
             */
        },
        true
    );


    /* =========================================================
       FOCUS OUT
       ========================================================= */

    document.addEventListener(
        'focusout',
        event => {

            const element =
                eventElement(event);

            const control =
                resolveLogicalControl(
                    element
                );

            if (!control) {
                return;
            }


            const state =
                controlStates.get(
                    control.root
                );

            if (!state ||
                !state.dirty) {

                return;
            }


            /*
             * Search/autocomplete:
             *
             * do NOT emit when the internal search box loses focus.
             * User may be clicking the API result next.
             */
            if (
                state.searchText &&
                state.waitingForSelection
            ) {

                return;
            }


            /*
             * Date picker:
             *
             * wait for actual final date.
             */
            if (
                isDateControl(
                    state.control
                )
            ) {

                setTimeout(
                    () => {

                        const actual =
                            readDateValue(
                                state.control
                            );


                        if (actual) {

                            state.finalValue =
                                actual;


                            emitFinal(
                                state,
                                'DATE_INPUT',
                                'DATE'
                            );
                        }

                    },
                    100
                );

                return;
            }


            /*
             * Normal input:
             *
             * read the final entered value after Angular
             * finishes updating the field.
             */
            setTimeout(
                () => {

                    const actual =
                        readValue(
                            state.control
                        );


                    if (actual != null) {

                        state.finalValue =
                            actual;
                    }


                    emitFinal(
                        state,
                        'INPUT',
                        detectInputType(
                            state.control
                        )
                    );

                },
                50
            );
        },
        true
    );


    /* =========================================================
       KEYBOARD
       ========================================================= */

    document.addEventListener(
        'keydown',
        event => {

            /*
             * We intentionally do not record Tab/Enter
             * as separate events for ordinary input completion.
             *
             * They are normally only interaction mechanics.
             *
             * Add KEY events separately only if your application
             * specifically needs keyboard behavior later.
             */
        },
        true
    );


    /* =========================================================
       ANGULAR SPA STAGE
       ========================================================= */

    function detectStageChange() {

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

            detectStageChange();

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

            detectStageChange();

            return result;
        };


    window.addEventListener(
        'popstate',
        detectStageChange,
        true
    );

})();
""";
}