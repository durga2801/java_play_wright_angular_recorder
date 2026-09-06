package com.example.angularrecorder.service;

import com.example.angularrecorder.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlaywrightAngularRecorder implements AutoCloseable {

    private static final String PREFIX = "__ANGULAR_RECORDER_EVENT__";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> rawEvents = new CopyOnWriteArrayList<>();

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

        context.addInitScript(RECORDING_SCRIPT);

        page = context.newPage();

        page.onConsoleMessage(message -> {
            String text = message.text();
            if (text != null && text.startsWith(PREFIX)) {
                String json = text.substring(PREFIX.length());
                try {
                    JsonNode node = mapper.readTree(json);
                    rawEvents.add(node);

                    System.out.printf(
                            "[RECORDED] %-18s input=%s value=%s%n",
                            text(node, "actionType"),
                            text(node, "input"),
                            valuePreview(node)
                    );
                } catch (Exception ex) {
                    System.err.println("Unable to parse recorder event: " + ex.getMessage());
                }
            }
        });

        page.navigate(url);
        page.waitForLoadState();

        System.out.println("Angular recorder opened: " + url);
        System.out.println("Use the browser normally.");
        System.out.println("Press ENTER in this terminal when recording is complete.");
    }

    public List<RecordedEvent> readEvents() {
        mergeFallbackEvents();

        List<RecordedEvent> result = new ArrayList<>();
        long seq = 1;

        for (JsonNode n : rawEvents) {
            result.add(toEvent(seq++, n));
        }

        return new EventNormalizer().normalize(result);
    }

    private RecordedEvent toEvent(long sequence, JsonNode n) {
        IdentifyBy identifyBy = null;
        JsonNode identifyNode = n.get("identifyBy");
        if (identifyNode != null && !identifyNode.isNull()) {
            Map<String, String> candidates = new LinkedHashMap<>();

            JsonNode candidateNode = identifyNode.get("candidates");
            if (candidateNode != null && candidateNode.isObject()) {
                candidateNode.fields().forEachRemaining(e -> {
                    String value = e.getValue().asText("");
                    if (!value.isBlank()) {
                        candidates.put(e.getKey(), value);
                    }
                });
            }

            identifyBy = new IdentifyBy(
                    text(identifyNode, "preferredStrategy"),
                    text(identifyNode, "preferredValue"),
                    candidates
            );
        }

        RecordedValue value = null;
        JsonNode valueNode = n.get("value");
        if (valueNode != null && !valueNode.isNull()) {
            if (valueNode.isTextual()) {
                value = new RecordedValue(
                        valueNode.asText(), null, null, null, null, null
                );
            } else {
                value = new RecordedValue(
                        text(valueNode, "raw"),
                        text(valueNode, "searchText"),
                        text(valueNode, "selectedValue"),
                        text(valueNode, "selectedText"),
                        text(valueNode, "fileName"),
                        bool(valueNode, "checked")
                );
            }
        }

        ContextInfo contextInfo = null;
        JsonNode contextNode = n.get("context");
        if (contextNode != null && !contextNode.isNull()) {
            contextInfo = new ContextInfo(
                    text(contextNode, "type"),
                    text(contextNode, "name")
            );
        }

        WaitInfo waitInfo = null;
        JsonNode waitNode = n.get("waitInfo");
        if (waitNode != null && !waitNode.isNull()) {
            waitInfo = new WaitInfo(
                    text(waitNode, "type"),
                    text(waitNode, "target"),
                    text(waitNode, "role")
            );
        }

        return new RecordedEvent(
                sequence,
                n.path("timestamp").asLong(System.currentTimeMillis()),
                n.path("stage").asLong(1L),
                text(n, "actionType"),
                text(n, "inputType"),
                text(n, "input"),
                identifyBy,
                value,
                contextInfo,
                waitInfo,
                text(n, "url")
        );
    }

    private void mergeFallbackEvents() {
        if (page == null || page.isClosed()) {
            return;
        }

        try {
            Object data = page.evaluate("() => window.__angularRecorderEvents || []");
            JsonNode array = mapper.valueToTree(data);
            if (!array.isArray()) {
                return;
            }

            Set<String> fingerprints = new HashSet<>();
            for (JsonNode e : rawEvents) {
                fingerprints.add(fingerprint(e));
            }

            for (JsonNode e : array) {
                if (fingerprints.add(fingerprint(e))) {
                    rawEvents.add(e);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String fingerprint(JsonNode n) {
        return text(n, "timestamp") + "|"
                + text(n, "actionType") + "|"
                + text(n, "input") + "|"
                + valuePreview(n);
    }

    private String valuePreview(JsonNode n) {
        JsonNode v = n.get("value");
        if (v == null || v.isNull()) {
            return "";
        }
        if (v.isTextual()) {
            return v.asText();
        }
        String[] names = {"raw", "searchText", "selectedValue", "selectedText", "fileName"};
        for (String name : names) {
            String value = text(v, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(name);
        if (child == null || child.isNull()) {
            return null;
        }
        String value = child.asText();
        return value.isBlank() ? null : value;
    }

    private Boolean bool(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return null;
        }
        return node.get(name).asBoolean();
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
  if (window.__angularRecorderInstalled) return;
  window.__angularRecorderInstalled = true;
  window.__angularRecorderEvents = [];

  const PREFIX = '__ANGULAR_RECORDER_EVENT__';
  const timers = new WeakMap();
  const pending = new WeakMap();
  let currentStage = 1;
  let pageBusy = false;
  let lastUrl = location.href;
  let lastMutationAt = Date.now();

  function markBusy() {
    pageBusy = true;
  }

  function markPotentialStageChange() {
    markBusy();
    setTimeout(() => {
      if (location.href !== lastUrl) {
        lastUrl = location.href;
        currentStage++;
      }
    }, 0);
  }

  function angularStable() {
    try {
      if (window.getAllAngularTestabilities) {
        const testabilities = window.getAllAngularTestabilities();
        if (testabilities && testabilities.length) {
          return testabilities.every(t => t.isStable());
        }
      }
    } catch (_) {}
    return true;
  }

  function documentReady() {
    return document.readyState === 'complete';
  }

  function visuallyBusy() {
    try {
      return !!document.querySelector(
        '[aria-busy="true"],' +
        '.mat-mdc-progress-spinner,' +
        '.mat-progress-spinner,' +
        '.mat-mdc-progress-bar,' +
        '.mat-progress-bar,' +
        '.p-progress-spinner,' +
        '.p-progressbar,' +
        '[class*="loading"][style*="display: block"]'
      );
    } catch (_) {
      return false;
    }
  }

  async function waitForPageReady(timeoutMs = 30000) {
    const started = Date.now();
    let stableSince = 0;

    while (Date.now() - started < timeoutMs) {
      const domStable = (Date.now() - lastMutationAt) >= 350;
      const ready = documentReady() && angularStable() && !visuallyBusy() && domStable;

      if (ready) {
        if (!stableSince) stableSince = Date.now();
        if (Date.now() - stableSince >= 300) {
          pageBusy = false;
          return true;
        }
      } else {
        stableSince = 0;
      }

      await new Promise(r => setTimeout(r, 100));
    }

    pageBusy = false;
    return false;
  }

  async function emitWhenReady(event) {
    if (pageBusy) {
      await waitForPageReady();
    }

    emit(event);
  }


  function clean(v) {
    if (v == null) return null;
    const s = String(v).replace(/\\s+/g, ' ').trim();
    return s || null;
  }

  function shortText(el) {
    if (!el) return null;
    const txt = clean(el.innerText || el.textContent);
    if (!txt || txt.length > 160) return null;
    return txt;
  }

  function eventElement(event) {
    const path = event && event.composedPath ? event.composedPath() : [];
    for (const item of path) {
      if (item && item.nodeType === Node.ELEMENT_NODE) return item;
    }
    return event ? event.target : null;
  }

  function closestMeaningful(el) {
    if (!el || !el.closest) return el;

    return el.closest([
      'input',
      'textarea',
      'select',
      'button',
      'a',
      '[contenteditable="true"]',
      '[role="textbox"]',
      '[role="combobox"]',
      '[role="searchbox"]',
      '[role="checkbox"]',
      '[role="radio"]',
      '[role="switch"]',
      '[role="option"]',
      '[role="button"]',
      '[role="link"]',
      '[role="menuitem"]',
      '[role="tab"]',
      '[role="slider"]',
      '[role="spinbutton"]',
      '[formControlName]',
      '[ngModel]',
      '[name]'
    ].join(',')) || el;
  }

  function labelledBy(el) {
    if (!el || !el.getAttribute) return null;
    const ids = clean(el.getAttribute('aria-labelledby'));
    if (!ids) return null;

    return clean(ids.split(/\\s+/)
      .map(id => document.getElementById(id))
      .filter(Boolean)
      .map(x => x.innerText || x.textContent)
      .join(' '));
  }

  function associatedLabel(el) {
    if (!el) return null;

    if (el.labels && el.labels.length) {
      const v = clean(Array.from(el.labels)
        .map(x => x.innerText || x.textContent)
        .join(' '));
      if (v) return v;
    }

    const id = clean(el.id);
    if (id) {
      try {
        const lbl = document.querySelector(`label[for="${CSS.escape(id)}"]`);
        if (lbl) {
          const v = clean(lbl.innerText || lbl.textContent);
          if (v) return v;
        }
      } catch (_) {}
    }

    const wrapping = el.closest && el.closest('label');
    if (wrapping) {
      const v = clean(wrapping.innerText || wrapping.textContent);
      if (v) return v;
    }

    const ariaLabelled = labelledBy(el);
    if (ariaLabelled) return ariaLabelled;

    const wrappers = [
      '.mat-mdc-form-field',
      '.mat-form-field',
      '.p-field',
      '.p-float-label',
      '.form-group',
      '.form-field',
      '.field',
      '.input-group',
      '[class*="form-field"]',
      '[class*="field"]'
    ];

    for (const selector of wrappers) {
      const wrapper = el.closest && el.closest(selector);
      if (!wrapper) continue;

      const label = wrapper.querySelector(
        'label,mat-label,.mat-mdc-floating-label,.mat-form-field-label,' +
        '.p-float-label label,[class*="label"]'
      );

      if (label) {
        const v = clean(label.innerText || label.textContent);
        if (v && v.length <= 160) return v;
      }
    }

    return null;
  }

  function angularFormControlName(el) {
    if (!el || !el.getAttribute) return null;
    return clean(
      el.getAttribute('formControlName') ||
      el.getAttribute('formcontrolname') ||
      el.getAttribute('ng-reflect-name')
    );
  }

  function accessibleName(el) {
    if (!el) return null;

    return clean(
      associatedLabel(el) ||
      el.getAttribute?.('aria-label') ||
      labelledBy(el) ||
      el.getAttribute?.('title') ||
      el.getAttribute?.('placeholder')
    );
  }

  function semanticInputName(el) {
    if (!el) return 'unnamed';

    const role = clean(el.getAttribute?.('role'));
    const tag = (el.tagName || '').toLowerCase();
    const type = clean(el.getAttribute?.('type'));

    const textAllowed =
      tag === 'button' ||
      tag === 'a' ||
      role === 'button' ||
      role === 'link' ||
      role === 'option' ||
      role === 'radio' ||
      role === 'checkbox' ||
      role === 'switch' ||
      role === 'menuitem';

    // Placeholder is only a final fallback for semantic identity.
    // It remains inside identifyBy.candidates, but should not create its own event.
    return clean(
      associatedLabel(el) ||
      el.getAttribute?.('aria-label') ||
      labelledBy(el) ||
      angularFormControlName(el) ||
      el.getAttribute?.('name') ||
      el.id ||
      (textAllowed ? shortText(el) : null) ||
      el.getAttribute?.('placeholder') ||
      type ||
      tag
    ) || 'unnamed';
  }

  function candidates(el) {
    const result = {};

    const label = associatedLabel(el);
    const ariaLabel = clean(el.getAttribute?.('aria-label'));
    const a11y = accessibleName(el);
    const formControlName = angularFormControlName(el);
    const name = clean(el.getAttribute?.('name'));
    const id = clean(el.id);
    const role = clean(el.getAttribute?.('role'));
    const placeholder = clean(el.getAttribute?.('placeholder'));
    const testId = clean(
      el.getAttribute?.('data-testid') ||
      el.getAttribute?.('data-test') ||
      el.getAttribute?.('data-cy')
    );
    const text = shortText(el);

    if (label) result.LABEL = label;
    if (ariaLabel) result.ARIA_LABEL = ariaLabel;
    if (a11y) result.ACCESSIBLE_NAME = a11y;
    if (formControlName) result.FORM_CONTROL_NAME = formControlName;
    if (name) result.NAME = name;
    if (id) result.ID = id;
    if (role) result.ROLE = role;
    if (placeholder) result.PLACEHOLDER = placeholder;
    if (testId) result.DATA_TESTID = testId;
    if (text) result.TEXT = text;

    if ((el.tagName || '').toLowerCase() === 'input') {
      const type = clean(el.getAttribute('type'));
      if (type) result.HTML_INPUT_TYPE = type;
    }

    return result;
  }

  function identifyBy(el) {
    const c = candidates(el);
    const input = semanticInputName(el);

    const priority = [
      'LABEL',
      'ARIA_LABEL',
      'ACCESSIBLE_NAME',
      'FORM_CONTROL_NAME',
      'NAME',
      'ID',
      'ROLE',
      'PLACEHOLDER',
      'DATA_TESTID',
      'TEXT'
    ];

    for (const strategy of priority) {
      if (!c[strategy]) continue;

      if (strategy === 'LABEL' ||
          strategy === 'ARIA_LABEL' ||
          strategy === 'ACCESSIBLE_NAME') {
        if (clean(c[strategy]) === clean(input)) {
          return {
            preferredStrategy: strategy,
            preferredValue: c[strategy],
            candidates: c
          };
        }
      }
    }

    for (const strategy of priority) {
      if (c[strategy]) {
        return {
          preferredStrategy: strategy,
          preferredValue: c[strategy],
          candidates: c
        };
      }
    }

    return {
      preferredStrategy: 'INPUT',
      preferredValue: input,
      candidates: c
    };
  }

  function currentDialogContext(el) {
    const dialog = el?.closest?.(
      '[role="dialog"],[aria-modal="true"],mat-dialog-container,.mat-mdc-dialog-container,.p-dialog'
    );

    if (!dialog) return null;

    return {
      type: 'DIALOG',
      name: clean(
        dialog.getAttribute('aria-label') ||
        labelledBy(dialog) ||
        dialog.querySelector?.('h1,h2,h3,[role="heading"]')?.textContent
      ) || 'dialog'
    };
  }

  function inputType(el) {
    const tag = (el?.tagName || '').toLowerCase();
    const role = clean(el?.getAttribute?.('role'));
    const type = clean(el?.getAttribute?.('type'))?.toLowerCase();

    if (type === 'file') return 'FILE';
    if (type === 'checkbox' || role === 'checkbox') return 'CHECKBOX';
    if (type === 'radio' || role === 'radio') return 'RADIO';
    if (role === 'switch') return 'TOGGLE';
    if (tag === 'select') return 'SELECT';
    if (role === 'combobox' || role === 'searchbox') return 'AUTOCOMPLETE';
    if (type === 'date' || type === 'datetime-local' || type === 'month' || type === 'time') return 'DATE';
    if (tag === 'textarea') return 'TEXTAREA';
    if (type === 'password') return 'PASSWORD';
    if (type === 'email') return 'EMAIL';
    if (type === 'number' || role === 'spinbutton') return 'NUMBER';
    if (type === 'tel') return 'TEL';
    if (type === 'url') return 'URL';
    if (type === 'search') return 'SEARCH';
    if (tag === 'input' || role === 'textbox') return 'TEXT';
    if (role === 'slider') return 'SLIDER';
    if (tag === 'button' || role === 'button') return 'BUTTON';
    if (tag === 'a' || role === 'link') return 'LINK';
    if (role === 'option') return 'OPTION';
    return 'CUSTOM';
  }

  function getValue(el) {
    if (!el) return null;

    if ('value' in el && el.value != null) {
      return clean(el.value);
    }

    const ariaValue = clean(el.getAttribute?.('aria-valuetext') || el.getAttribute?.('aria-valuenow'));
    if (ariaValue) return ariaValue;

    return null;
  }

  function isChecked(el) {
    if (!el) return null;
    if ('checked' in el) return !!el.checked;

    const aria = clean(el.getAttribute?.('aria-checked'));
    if (aria === 'true') return true;
    if (aria === 'false') return false;

    return null;
  }

  function emit(event) {
    const full = {
      timestamp: Date.now(),
      stage: currentStage,
      url: location.href,
      ...event
    };

    window.__angularRecorderEvents.push(full);
    console.log(PREFIX + JSON.stringify(full));
  }

  function baseEvent(el, actionType, typeOverride) {
    return {
      actionType,
      inputType: typeOverride || inputType(el),
      input: semanticInputName(el),
      identifyBy: identifyBy(el),
      context: currentDialogContext(el)
    };
  }

  function emitEditable(el, actionType, rawValue) {
    const event = baseEvent(el, actionType);

    event.value = {
      raw: clean(rawValue)
    };

    emit(event);
  }

  function scheduleEditable(el, actionType) {
    const old = timers.get(el);
    if (old) clearTimeout(old);

    pending.set(el, {
      actionType,
      value: getValue(el)
    });

    const timer = setTimeout(() => {
      const p = pending.get(el);
      if (!p) return;
      emitEditable(el, p.actionType, getValue(el));
      pending.delete(el);
      timers.delete(el);
    }, 450);

    timers.set(el, timer);
  }

  function flushEditable(el) {
    if (!pending.has(el)) return;

    const old = timers.get(el);
    if (old) clearTimeout(old);

    const p = pending.get(el);
    emitEditable(el, p.actionType, getValue(el));

    pending.delete(el);
    timers.delete(el);
  }

  function editableControlFromVisualSurface(el) {
    if (!el) return null;

    if (el.matches?.('input,textarea,select,[contenteditable="true"],[role="textbox"],[role="combobox"],[role="searchbox"]')) {
      return el;
    }

    const label = el.closest?.('label');
    if (label) {
      const forId = clean(label.getAttribute('for'));
      if (forId) {
        try {
          const control = document.getElementById(forId);
          if (control) return control;
        } catch (_) {}
      }

      const nested = label.querySelector?.(
        'input,textarea,select,[contenteditable="true"],[role="textbox"],[role="combobox"],[role="searchbox"]'
      );
      if (nested) return nested;
    }

    const wrapper = el.closest?.(
      '.mat-mdc-form-field,.mat-form-field,.p-field,.p-float-label,.form-group,.form-field,.field,.input-group,[class*="form-field"]'
    );

    if (wrapper) {
      const nested = wrapper.querySelector?.(
        'input,textarea,select,[contenteditable="true"],[role="textbox"],[role="combobox"],[role="searchbox"]'
      );
      if (nested) return nested;
    }

    return null;
  }

  function isSearchLike(el) {
    const role = clean(el?.getAttribute?.('role'));
    const type = clean(el?.getAttribute?.('type'))?.toLowerCase();
    const ariaAuto = clean(el?.getAttribute?.('aria-autocomplete'));

    return role === 'combobox' ||
           role === 'searchbox' ||
           type === 'search' ||
           !!ariaAuto;
  }

  function isDateLike(el) {
    const type = clean(el?.getAttribute?.('type'))?.toLowerCase();
    return ['date','datetime-local','month','time','week'].includes(type);
  }

  document.addEventListener('input', event => {
    const el = closestMeaningful(eventElement(event));
    if (!el) return;

    const type = inputType(el);

    if (['CHECKBOX','RADIO','TOGGLE','FILE','SELECT'].includes(type)) return;

    let actionType = 'INPUT';
    if (isSearchLike(el)) actionType = 'SEARCH';
    if (isDateLike(el)) actionType = 'DATE_INPUT';

    scheduleEditable(el, actionType);
  }, true);

  document.addEventListener('change', event => {
    const el = closestMeaningful(eventElement(event));
    if (!el) return;

    flushEditable(el);

    const type = inputType(el);

    if (type === 'FILE') {
      const files = el.files ? Array.from(el.files) : [];
      emit({
        ...baseEvent(el, 'FILE_UPLOAD', 'FILE'),
        value: {
          fileName: files.length ? files.map(f => f.name).join(', ') : null
        }
      });
      return;
    }

    if (type === 'CHECKBOX' || type === 'TOGGLE') {
      emit({
        ...baseEvent(el, isChecked(el) ? 'CHECK' : 'UNCHECK', type),
        value: {
          raw: clean(el.value),
          checked: isChecked(el)
        }
      });
      return;
    }

    if (type === 'RADIO') {
      if (isChecked(el) === false) return;
      emit({
        ...baseEvent(el, 'RADIO', 'RADIO'),
        value: {
          raw: clean(el.value),
          selectedValue: clean(el.value),
          selectedText: semanticInputName(el),
          checked: true
        }
      });
      return;
    }

    if ((el.tagName || '').toLowerCase() === 'select') {
      const option = el.selectedOptions?.[0];
      emit({
        ...baseEvent(el, 'SELECT', 'SELECT'),
        value: {
          raw: clean(el.value),
          selectedValue: clean(el.value),
          selectedText: clean(option?.textContent)
        }
      });
      return;
    }

    let actionType = 'INPUT';
    if (isSearchLike(el)) actionType = 'SEARCH';
    if (isDateLike(el)) actionType = 'DATE_INPUT';
    emitEditable(el, actionType, getValue(el));
  }, true);

  document.addEventListener('blur', event => {
    const el = closestMeaningful(eventElement(event));
    if (el) flushEditable(el);
  }, true);

  document.addEventListener('focusout', event => {
    const el = closestMeaningful(eventElement(event));
    if (el) flushEditable(el);
  }, true);

  document.addEventListener('click', event => {
    let el = closestMeaningful(eventElement(event));
    if (!el) return;

    // Clicking placeholder/label/form-field chrome is only focus behavior.
    // Resolve it to the actual editable control and do not record a separate CLICK.
    const editable = editableControlFromVisualSurface(el);
    if (editable) {
      const editableType = inputType(editable);
      if (['TEXT','PASSWORD','EMAIL','NUMBER','TEL','URL','SEARCH','TEXTAREA','AUTOCOMPLETE','DATE'].includes(editableType)) {
        return;
      }
      el = editable;
    }

    const type = inputType(el);
    const role = clean(el.getAttribute?.('role'));

    if (type === 'CHECKBOX' || type === 'RADIO' || type === 'TOGGLE') {
      // change handler records the final state.
      return;
    }

    if (type === 'OPTION' || role === 'option') {
      const text = shortText(el) || semanticInputName(el);
      emit({
        ...baseEvent(el, 'SELECT', 'OPTION'),
        value: {
          raw: text,
          selectedValue: clean(el.getAttribute?.('value')) || text,
          selectedText: text
        },
        waitInfo: {
          type: 'OPTION_VISIBLE',
          target: text,
          role: 'option'
        }
      });
      return;
    }

    const tag = (el.tagName || '').toLowerCase();
    const rawType = clean(el.getAttribute?.('type'))?.toLowerCase();

    if (tag === 'input' &&
        !['button','submit','reset','file'].includes(rawType || '') &&
        !['checkbox','radio'].includes(rawType || '')) {
      return;
    }

    if (tag === 'textarea' || tag === 'select' ||
        role === 'textbox' || role === 'combobox' || role === 'searchbox') {
      return;
    }

    const inputName = semanticInputName(el);
    const popupTrigger = el.getAttribute?.('aria-haspopup');
    const dialogTarget = el.getAttribute?.('aria-controls');

    emit({
      ...baseEvent(
        el,
        popupTrigger || dialogTarget ? 'OPEN_POPUP' : 'CLICK'
      ),
      value: {
        raw: shortText(el) || inputName
      }
    });
  }, true);

  document.addEventListener('keydown', event => {
    if (!['Enter', 'Tab', 'Escape'].includes(event.key)) return;

    const el = closestMeaningful(eventElement(event));
    if (!el) return;

    emit({
      ...baseEvent(el, 'KEY'),
      value: {
        raw: event.key
      }
    });
  }, true);

  // Capture dynamically rendered Angular/CDK overlays and custom option containers.
  const observer = new MutationObserver(() => {
    lastMutationAt = Date.now();
  });

  window.addEventListener('beforeunload', markBusy, true);
  window.addEventListener('load', () => {
    lastMutationAt = Date.now();
    setTimeout(() => { pageBusy = false; }, 300);
  }, true);

  document.addEventListener('click', event => {
    const el = closestMeaningful(eventElement(event));
    if (!el) return;

    const tag = (el.tagName || '').toLowerCase();
    const role = clean(el.getAttribute?.('role'));
    const type = clean(el.getAttribute?.('type'))?.toLowerCase();

    if (tag === 'a' ||
        type === 'submit' ||
        role === 'link' ||
        el.getAttribute?.('routerlink') ||
        el.getAttribute?.('ng-reflect-router-link')) {
      markPotentialStageChange();
    }
  }, true);

  const originalPushState = history.pushState.bind(history);
  history.pushState = function(...args) {
    const result = originalPushState(...args);
    markPotentialStageChange();
    return result;
  };

  const originalReplaceState = history.replaceState.bind(history);
  history.replaceState = function(...args) {
    const result = originalReplaceState(...args);
    markPotentialStageChange();
    return result;
  };

  window.addEventListener('popstate', markPotentialStageChange, true);

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();
""";
}
