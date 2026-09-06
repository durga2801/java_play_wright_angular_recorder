package com.example.angularrecorder.service;

import com.example.angularrecorder.model.IdentifyBy;
import com.example.angularrecorder.model.RecordedEvent;
import com.example.angularrecorder.model.RecordedValue;
import com.example.angularrecorder.model.WaitInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class EventNormalizer {

    public List<RecordedEvent> normalize(List<RecordedEvent> raw) {
        List<RecordedEvent> result = new ArrayList<>();

        for (RecordedEvent event : raw) {

            /*
             * Do not keep a separate placeholder-only interaction when it belongs
             * to the same real input control. Placeholder is locator metadata, not
             * a separate user action.
             */
            if (isPlaceholderOnlyNoise(event)) {
                int previous = findPreviousMatchingInput(result, event);
                if (previous >= 0) {
                    continue;
                }
            }

            /*
             * INPUT/SEARCH/DATE_INPUT can fire many times while typing.
             * Keep only the latest value for the same semantic field.
             *
             * Matching uses all identifyBy aliases (label/formControlName/name/id/
             * placeholder/etc.), so a field does not become a second event just
             * because one browser event exposed the placeholder while another
             * exposed the label.
             */
            if (isEditable(event)) {
                int previous = findPreviousMatchingEditable(result, event);

                if (previous >= 0) {
                    RecordedEvent old = result.get(previous);
                    result.set(previous, mergeEditable(old, event));
                    continue;
                }
            }

            // Collapse SEARCH + SELECT into one SEARCH_AND_SELECT event.
            if ("SELECT".equals(event.actionType())) {
                int previous = findPreviousSearch(result, event);

                if (previous >= 0) {
                    RecordedEvent search = result.get(previous);

                    RecordedEvent merged = new RecordedEvent(
                            event.sequence(),
                            event.timestamp(),
                            Math.max(search.stage(), event.stage()),
                            "SEARCH_AND_SELECT",
                            "AUTOCOMPLETE",
                            choosePrimaryInput(search, event),
                            chooseIdentifyBy(search, event),
                            new RecordedValue(
                                    null,
                                    raw(search),
                                    firstNonBlank(selectedValue(event), raw(event)),
                                    firstNonBlank(selectedText(event), raw(event)),
                                    null,
                                    null
                            ),
                            search.context() != null
                                    ? search.context()
                                    : event.context(),
                            new WaitInfo(
                                    "OPTION_VISIBLE",
                                    firstNonBlank(
                                            selectedText(event),
                                            raw(event)
                                    ),
                                    "option"
                            ),
                            event.url()
                    );

                    result.set(previous, merged);
                    continue;
                }
            }

            /*
             * If a CLICK/OPEN_POPUP event only represents the visual placeholder
             * surface of an input that immediately becomes INPUT/SEARCH, keep the
             * actual input event and discard the placeholder click.
             */
            if (isInputSurfaceClick(event)) {
                result.add(event);
                continue;
            }

            result.add(event);
        }

        // Second pass removes a placeholder/input-surface click when the next
        // semantic event is for the same field.
        result = removeInputSurfaceNoise(result);

        List<RecordedEvent> resequenced = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            resequenced.add(result.get(i).withSequence(i + 1L));
        }
        return resequenced;
    }

    private List<RecordedEvent> removeInputSurfaceNoise(List<RecordedEvent> source) {
        List<RecordedEvent> out = new ArrayList<>();

        for (int i = 0; i < source.size(); i++) {
            RecordedEvent current = source.get(i);

            if (isInputSurfaceClick(current) && i + 1 < source.size()) {
                RecordedEvent next = source.get(i + 1);

                if (isEditable(next) && sameControl(current, next)) {
                    continue;
                }
            }

            out.add(current);
        }

        return out;
    }

    private RecordedEvent mergeEditable(RecordedEvent old, RecordedEvent latest) {
        return new RecordedEvent(
                latest.sequence(),
                latest.timestamp(),
                Math.max(old.stage(), latest.stage()),
                latest.actionType(),
                firstNonBlank(latest.inputType(), old.inputType()),
                choosePrimaryInput(old, latest),
                chooseIdentifyBy(old, latest),
                latest.value() != null ? latest.value() : old.value(),
                latest.context() != null ? latest.context() : old.context(),
                latest.waitInfo() != null ? latest.waitInfo() : old.waitInfo(),
                latest.url() != null ? latest.url() : old.url()
        );
    }

    private int findPreviousMatchingEditable(List<RecordedEvent> events, RecordedEvent candidate) {
        for (int i = events.size() - 1; i >= 0; i--) {
            RecordedEvent previous = events.get(i);

            // Stop merging across a meaningful action on another control.
            if (!isEditable(previous)) {
                if (sameControl(previous, candidate) && isInputSurfaceClick(previous)) {
                    continue;
                }
                break;
            }

            if (sameControl(previous, candidate)) {
                return i;
            }
        }
        return -1;
    }

    private int findPreviousMatchingInput(List<RecordedEvent> events, RecordedEvent candidate) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (sameControl(events.get(i), candidate)) {
                return i;
            }
        }
        return -1;
    }

    private int findPreviousSearch(List<RecordedEvent> events, RecordedEvent select) {
        for (int i = events.size() - 1; i >= 0; i--) {
            RecordedEvent previous = events.get(i);

            if ("SEARCH".equals(previous.actionType()) && compatibleSearchSelection(previous, select)) {
                return i;
            }

            // An option SELECT normally immediately follows its search.
            if (!isInputSurfaceClick(previous) && !"KEY".equals(previous.actionType())) {
                break;
            }
        }
        return -1;
    }

    private boolean compatibleSearchSelection(RecordedEvent search, RecordedEvent select) {
        if ("option".equalsIgnoreCase(candidate(select, "ROLE"))) {
            return true;
        }
        return sameControl(search, select)
                || Objects.equals(search.context(), select.context());
    }

    private boolean sameControl(RecordedEvent a, RecordedEvent b) {
        if (a == null || b == null) {
            return false;
        }

        Set<String> aliasesA = aliases(a);
        Set<String> aliasesB = aliases(b);

        for (String alias : aliasesA) {
            if (aliasesB.contains(alias)) {
                return true;
            }
        }

        return false;
    }

    private Set<String> aliases(RecordedEvent event) {
        Set<String> aliases = new LinkedHashSet<>();

        addAlias(aliases, event.input());

        IdentifyBy id = event.identifyBy();
        if (id != null) {
            addAlias(aliases, id.preferredValue());

            if (id.candidates() != null) {
                for (Map.Entry<String, String> entry : id.candidates().entrySet()) {
                    // HTML_INPUT_TYPE is descriptive metadata, not identity.
                    if (!"HTML_INPUT_TYPE".equalsIgnoreCase(entry.getKey())) {
                        addAlias(aliases, entry.getValue());
                    }
                }
            }
        }

        return aliases;
    }

    private void addAlias(Set<String> aliases, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            aliases.add(normalized);
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String n = value.trim().toLowerCase().replaceAll("\\s+", " ");
        return n.isBlank() ? null : n;
    }

    private String choosePrimaryInput(RecordedEvent first, RecordedEvent latest) {
        // Prefer a semantic label/accessibility/form-control identity over placeholder.
        String[] strategies = {
                "LABEL",
                "ARIA_LABEL",
                "ACCESSIBLE_NAME",
                "FORM_CONTROL_NAME",
                "NAME",
                "ID",
                "ROLE",
                "DATA_TESTID",
                "TEXT",
                "PLACEHOLDER"
        };

        for (String strategy : strategies) {
            String value = firstNonBlank(
                    candidate(latest, strategy),
                    candidate(first, strategy)
            );
            if (value != null) {
                return value;
            }
        }

        return firstNonBlank(latest.input(), first.input());
    }

    private IdentifyBy chooseIdentifyBy(RecordedEvent first, RecordedEvent latest) {
        if (latest.identifyBy() == null) return first.identifyBy();
        if (first.identifyBy() == null) return latest.identifyBy();

        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        merged.putAll(first.identifyBy().candidates());
        merged.putAll(latest.identifyBy().candidates());

        String preferredStrategy = latest.identifyBy().preferredStrategy();
        String preferredValue = latest.identifyBy().preferredValue();

        if ("PLACEHOLDER".equalsIgnoreCase(preferredStrategy)) {
            String[] better = {
                    "LABEL",
                    "ARIA_LABEL",
                    "ACCESSIBLE_NAME",
                    "FORM_CONTROL_NAME",
                    "NAME",
                    "ID",
                    "ROLE",
                    "DATA_TESTID",
                    "TEXT"
            };

            for (String strategy : better) {
                String v = merged.get(strategy);
                if (v != null && !v.isBlank()) {
                    preferredStrategy = strategy;
                    preferredValue = v;
                    break;
                }
            }
        }

        return new IdentifyBy(preferredStrategy, preferredValue, merged);
    }

    private boolean isEditable(RecordedEvent event) {
        return event != null && (
                "INPUT".equals(event.actionType())
                        || "SEARCH".equals(event.actionType())
                        || "DATE_INPUT".equals(event.actionType())
        );
    }

    private boolean isInputSurfaceClick(RecordedEvent event) {
        if (event == null) return false;

        if (!"CLICK".equals(event.actionType()) && !"OPEN_POPUP".equals(event.actionType())) {
            return false;
        }

        String type = event.inputType();
        return "TEXT".equals(type)
                || "SEARCH".equals(type)
                || "AUTOCOMPLETE".equals(type)
                || "TEXTAREA".equals(type)
                || "DATE".equals(type)
                || "CUSTOM".equals(type);
    }

    private boolean isPlaceholderOnlyNoise(RecordedEvent event) {
        if (event == null || event.identifyBy() == null) return false;

        String preferred = event.identifyBy().preferredStrategy();
        return "PLACEHOLDER".equalsIgnoreCase(preferred)
                && ("CLICK".equals(event.actionType())
                    || "OPEN_POPUP".equals(event.actionType()));
    }

    private String candidate(RecordedEvent event, String strategy) {
        if (event == null || event.identifyBy() == null
                || event.identifyBy().candidates() == null) {
            return null;
        }
        return event.identifyBy().candidates().get(strategy);
    }

    private String raw(RecordedEvent e) {
        return e != null && e.value() != null ? e.value().raw() : null;
    }

    private String selectedValue(RecordedEvent e) {
        return e != null && e.value() != null ? e.value().selectedValue() : null;
    }

    private String selectedText(RecordedEvent e) {
        return e != null && e.value() != null ? e.value().selectedText() : null;
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
