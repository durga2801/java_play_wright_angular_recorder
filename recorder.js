(() => {
if (window.__angularRecorderInstalled) {
return;
}
window.__angularRecorderInstalled = true;
window.__angularRecorderEvents = [];

const PREFIX = '__ANGULAR_RECORDER_EVENT__';
const timers = new WeakMap();
const pending = new WeakMap();
const lastLogicalEmissions = new WeakMap();
const selectionStates = new WeakMap();
const multiSelectTimers = new WeakMap();

let currentStage = 1;
let lastUrl = location.href;
let lastMutationAt = Date.now();
let activeLogicalControl = null;
let eventCounter = 0;

function clean(value) {
if (value == null) {
return null;
}
const result = String(value)
.replace(/\s+/g, ' ')
.trim();
return result || null;
}

function shortText(element) {
if (!element) {
return null;
}
const text = clean(
element.innerText ||
element.textContent
);
if (!text || text.length > 180) {
return null;
}
return text;
}

function eventElement(event) {
const path =
event && event.composedPath
? event.composedPath()
: [];

for (const item of path) {
if (
item &&
item.nodeType === Node.ELEMENT_NODE
) {
return item;
}
}

return event ? event.target : null;
}

function eventPath(event) {
if (event && event.composedPath) {
return event
.composedPath()
.filter(
x =>
x &&
x.nodeType === Node.ELEMENT_NODE
);
}
return [];
}

function ancestors(element) {
const result = [];
let current = element;

while (
current &&
current !== document.documentElement
) {
result.push(current);
current = current.parentElement;
}

return result;
}

function isNativeOrSemanticControl(element) {
if (!element || !element.matches) {
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

function closestMeaningful(event) {
const path = eventPath(event);

for (const node of path) {
if (isNativeOrSemanticControl(node)) {
return node;
}
}

for (const node of path) {
if (isLogicalAngularHost(node)) {
return node;
}
}

return eventElement(event);
}

function isLogicalAngularHost(element) {
if (!element || !element.getAttribute) {
return false;
}

const tag = (
element.tagName || ''
).toLowerCase();

if (tag.includes('-')) {
return true;
}

if (
element.hasAttribute('formControlName') ||
element.hasAttribute('formcontrolname') ||
element.hasAttribute('controlName') ||
element.hasAttribute('controlname') ||
element.hasAttribute('ngModel') ||
element.hasAttribute('ngmodel')
) {
return true;
}

return false;
}

function hasControlBinding(element) {
if (!element || !element.getAttribute) {
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

function findLogicalAngularHost(nativeElement, event) {
const nodes = [
...eventPath(event),
...ancestors(nativeElement)
];

for (const node of nodes) {
if (hasControlBinding(node)) {
return node;
}
}

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

function controlName(nativeElement, customHost) {
const elements = [
nativeElement,
customHost
];

for (const element of elements) {
if (!element || !element.getAttribute) {
continue;
}

const value = clean(
element.getAttribute('formControlName') ||
element.getAttribute('formcontrolname') ||
element.getAttribute('controlName') ||
element.getAttribute('controlname') ||
element.getAttribute('name') ||
element.getAttribute('ng-reflect-name')
);

if (value) {
return value;
}
}

return null;
}

function labelledBy(element) {
if (!element || !element.getAttribute) {
return null;
}

const ids = clean(
element.getAttribute('aria-labelledby')
);

if (!ids) {
return null;
}

return clean(
ids
.split(/\s+/)
.map(
id =>
document.getElementById(id)
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

function cleanLabelText(label) {
if (!label) {
return null;
}

const clone = label.cloneNode(true);

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

function associatedLabel(element) {
if (!element) {
return null;
}

if (
element.labels &&
element.labels.length
) {
const result = clean(
Array.from(element.labels)
.map(cleanLabelText)
.filter(Boolean)
.join(' ')
);

if (result) {
return result;
}
}

const id = clean(element.id);

if (id) {
try {
const label =
document.querySelector(
`label[for="${CSS.escape(id)}"]`
);

if (label) {
return cleanLabelText(label);
}
} catch (_) {
}
}

const wrapping =
element.closest
? element.closest('label')
: null;

if (wrapping) {
const result =
cleanLabelText(wrapping);

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
customHost
) {
const source =
customHost ||
nativeElement;

if (!source || !source.closest) {
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
customHost
) {
const container =
findFormContainer(
nativeElement,
customHost
);

if (!container) {
return null;
}

const labels =
Array.from(
container.querySelectorAll('label')
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

function visibleLabel(
nativeElement,
customHost
) {
return clean(
associatedLabel(nativeElement) ||
nativeElement
?.getAttribute
?.('aria-label') ||
labelledBy(nativeElement) ||
customHost
?.getAttribute
?.('aria-label') ||
labelledBy(customHost) ||
labelFromContainer(
nativeElement,
customHost
)
);
}

function resolveControl(event) {
const nativeElement =
closestMeaningful(event);

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

function detectInputType(control) {
const element =
control.nativeElement;

const host =
control.customHost;

const tag =
(
element?.tagName || ''
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

if (element?.isContentEditable) {
return 'RICH_TEXT';
}

if (host) {
const hasDate =
host.querySelector(
'input[type="date"],' +
'input[type="datetime-local"],' +
'input[type="month"],' +
'input[type="week"],' +
'input[type="time"]'
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

if (
hostName.includes('date') ||
hostName.includes('calendar') ||
/(^|[^a-z])(date|dob|day|month|year)([^a-z]|$)/i
.test(dateHint || '')
) {
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
'input[type="checkbox"],' +
'[role="checkbox"]'
);

if (hasCheckbox) {
return 'CHECKBOX';
}

const hasRadio =
host.querySelector(
'input[type="radio"],' +
'[role="radio"]'
);

if (hasRadio) {
return 'RADIO';
}

const hasSearch =
host.querySelector(
'input[type="search"],' +
'[role="searchbox"],' +
'[role="combobox"],' +
'input[aria-autocomplete]'
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

function candidates(control) {
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
associatedLabel(element) ||
ariaLabel ||
labelledBy(element)
);

if (accessible) {
result.ACCESSIBLE_NAME =
accessible;
}

if (control.controlName) {
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

function semanticInputName(control) {
const c =
candidates(control);

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

function identifyBy(control) {
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
preferredStrategy: strategy,
preferredValue: c[strategy],
candidates: c
};
}
}

return {
preferredStrategy: 'INPUT',
preferredValue:
semanticInputName(control),
candidates: c
};
}

function elementValue(element) {
if (!element) {
return null;
}

const tag =
(element.tagName || '')
.toLowerCase();

const type =
clean(
element.getAttribute?.('type')
)?.toLowerCase();

if (
type === 'radio' ||
type === 'checkbox'
) {
if (
'checked' in element &&
!element.checked
) {
return null;
}
}

if (tag === 'select') {
const option =
element.selectedOptions?.[0];

return clean(
element.value ||
option?.textContent
);
}

if (element.isContentEditable) {
return clean(element.innerText);
}

if (
'value' in element &&
element.value != null
) {
const value =
clean(element.value);

if (value) {
return value;
}
}

return clean(
element.getAttribute?.('aria-valuetext') ||
element.getAttribute?.('aria-valuenow') ||
element.getAttribute?.('data-value') ||
element.getAttribute?.('ng-reflect-model')
);
}

function currentValue(control) {
if (!control) {
return null;
}

const elements = [];

const push = element => {
if (
element &&
!elements.includes(element)
) {
elements.push(element);
}
};

push(control.nativeElement);

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
'.selected-value,' +
'.selection-value,' +
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
return (
control?.customHost ||
control?.nativeElement ||
null
);
}

function snapshotLogicalControl(control) {
if (!control) {
return null;
}

return {
...control,
recorderIdentity: {
input:
semanticInputName(control),
identifyBy:
identifyBy(control),
inputType:
detectInputType(control),
context:
dialogContext(control)
}
};
}

function resetSelectionState(control) {
const key =
logicalKey(control);

if (!key) {
return;
}

const timer =
multiSelectTimers.get(key);

if (timer) {
clearTimeout(timer);
multiSelectTimers.delete(key);
}

const state =
selectionStates.get(key);

if (state) {
state.searchText = null;
state.selectedValues.clear();
state.selectedTexts.clear();
}
}

function activateLogicalControl(control) {
if (!control) {
return null;
}

const nextKey =
logicalKey(control);

const previousKey =
logicalKey(activeLogicalControl);

if (
previousKey &&
nextKey &&
previousKey !== nextKey
) {
resetSelectionState(
activeLogicalControl
);
}

activeLogicalControl =
snapshotLogicalControl(control);

const state =
selectionState(
activeLogicalControl
);

if (state) {
state.control =
activeLogicalControl;
}

return activeLogicalControl;
}

function logicalAction(control) {
const type =
detectInputType(control);

if (type === 'DATE') {
return 'DATE_INPUT';
}

if (
type === 'AUTOCOMPLETE' ||
type === 'SEARCH' ||
type === 'SELECT' ||
type === 'OPTION'
) {
return 'SELECT';
}

return 'INPUT';
}

function emitLogicalValue(
control,
value,
actionType = null
) {
const actual =
clean(value);

if (!control || !actual) {
return false;
}

const key =
logicalKey(control);

if (key) {
const previous =
lastLogicalEmissions.get(key);

if (
previous &&
previous.value === actual &&
Date.now() - previous.time < 1000
) {
return false;
}

lastLogicalEmissions.set(
key,
{
value: actual,
actionType:
actionType ||
logicalAction(control),
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
attempt = 0
) {
if (
!control ||
attempt > 12
) {
return;
}

setTimeout(
() => {
const actual =
currentValue(control);

if (
actual &&
actual !== beforeValue
) {
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

function checkedState(control) {
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
element.getAttribute?.('aria-checked')
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

state.control =
control;

return state;
}

function isSearchEntryElement(element) {
if (!element) {
return false;
}

const type =
clean(
element.getAttribute?.('type')
)?.toLowerCase();

const role =
clean(
element.getAttribute?.('role')
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
element.closest?.(
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
element.getAttribute?.('aria-label') ||
element.getAttribute?.('data-label') ||
shortText(element)
);
}

function optionValue(element) {
if (!element) {
return null;
}

return clean(
element.getAttribute?.('value') ||
element.getAttribute?.('data-value') ||
element.getAttribute?.('ng-reflect-value') ||
optionText(element)
);
}

function emitSelection(
control,
rawValue,
selectedValue,
selectedText,
actionType
) {
const actual =
clean(rawValue) ||
clean(selectedValue) ||
clean(selectedText);

if (!control || !actual) {
return false;
}

const key =
logicalKey(control);

if (key) {
const previous =
lastLogicalEmissions.get(key);

if (
previous &&
previous.value === actual &&
previous.actionType === actionType &&
Date.now() - previous.time < 1200
) {
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
actionType || 'SELECT'
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

function scheduleMultiSelectEmit(control) {
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

if (
!values.length &&
!texts.length
) {
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

function recordOverlayCheckbox(element) {
if (
!activeLogicalControl ||
!element
) {
return false;
}

const owner =
activeLogicalControl;

const state =
selectionState(owner);

if (!state) {
return false;
}

const checked =
'checked' in element
? !!element.checked
: element
.getAttribute
?.('aria-checked') === 'true';

const container =
element.closest?.(
'[role="option"],' +
'[role="menuitemcheckbox"],' +
'label,li,' +
'[class*="option"],' +
'[class*="item"]'
) || element;

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

scheduleMultiSelectEmit(owner);

return true;
}

function dialogContext(control) {
const element =
control.nativeElement;

const dialog =
element?.closest?.(`
[role="dialog"],
[aria-modal="true"]
`);

if (!dialog) {
return null;
}

return {
type: 'DIALOG',
name:
clean(
dialog.getAttribute('aria-label') ||
labelledBy(dialog) ||
dialog
.querySelector(
'h1,h2,h3,[role="heading"]'
)
?.textContent
) ||
'dialog'
};
}

function emit(event) {
const now =
Date.now();

const full = {
eventId:
`${now}-${++eventCounter}`,
timestamp: now,
stage: currentStage,
url: location.href,
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
typeOverride
) {
const snapshot =
control?.recorderIdentity;

return {
actionType,

inputType:
typeOverride ||
snapshot?.inputType ||
detectInputType(control),

input:
snapshot?.input ||
semanticInputName(control),

identifyBy:
snapshot?.identifyBy ||
identifyBy(control),

context:
snapshot?.context ||
dialogContext(control)
};
}

function editableAction(control) {
const type =
detectInputType(control);

if (
type === 'AUTOCOMPLETE' ||
type === 'SEARCH'
) {
return 'SEARCH';
}

if (type === 'DATE') {
return 'DATE_INPUT';
}

return 'INPUT';
}

function scheduleEditable(control) {
const element =
control.nativeElement;

if (!element) {
return;
}

const oldTimer =
timers.get(element);

if (oldTimer) {
clearTimeout(oldTimer);
}

pending.set(
element,
{
control,
actionType:
editableAction(control)
}
);

const timer =
setTimeout(
() => {
const item =
pending.get(element);

if (!item) {
return;
}

emitLogicalValue(
item.control,
currentValue(item.control),
item.actionType
);

pending.delete(element);
timers.delete(element);
},
500
);

timers.set(
element,
timer
);
}

function flushEditable(control) {
const element =
control.nativeElement;

if (
!element ||
!pending.has(element)
) {
return false;
}

const oldTimer =
timers.get(element);

if (oldTimer) {
clearTimeout(oldTimer);
}

const item =
pending.get(element);

emitLogicalValue(
item.control,
currentValue(item.control),
item.actionType
);

pending.delete(element);
timers.delete(element);

return true;
}

document.addEventListener(
'focusin',
event => {
const control =
resolveControl(event);

if (
control?.customHost &&
hasControlBinding(
control.customHost
)
) {
activateLogicalControl(
control
);
}
},
true
);

document.addEventListener(
'input',
event => {
const control =
resolveControl(event);

const type =
detectInputType(control);

if (
control.customHost &&
hasControlBinding(
control.customHost
) &&
isSearchEntryElement(
control.clickedElement ||
control.nativeElement
)
) {
const activeControl =
activateLogicalControl(control);

const state =
selectionState(activeControl);

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
type === 'CHECKBOX' ||
type === 'RADIO' ||
type === 'TOGGLE' ||
type === 'FILE' ||
type === 'SELECT'
) {
return;
}

scheduleEditable(control);
},
true
);

document.addEventListener(
'change',
event => {
const control =
resolveControl(event);

const flushed =
flushEditable(control);

const element =
control.nativeElement;

const clicked =
control.clickedElement ||
element;

const type =
detectInputType(control);

if (
activeLogicalControl &&
isOverlayElement(clicked) &&
(
element
?.getAttribute
?.('type') === 'checkbox' ||
element
?.getAttribute
?.('role') === 'checkbox' ||
element
?.getAttribute
?.('role') === 'menuitemcheckbox'
)
) {
if (recordOverlayCheckbox(element)) {
return;
}
}

if (type === 'FILE') {
const files =
element.files
? Array.from(element.files)
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
.map(f => f.name)
.join(', ')
: null
}
});

return;
}

if (
type === 'CHECKBOX' ||
type === 'TOGGLE'
) {
const checked =
checkedState(control);

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
currentValue(control),
checked
}
});

return;
}

if (type === 'RADIO') {
const checked =
checkedState(control);

if (checked === false) {
return;
}

const value =
currentValue(control);

emit({
...baseEvent(
control,
'RADIO',
'RADIO'
),
value: {
raw: value,
selectedValue: value,
selectedText:
semanticInputName(control),
checked: true
}
});

return;
}

if (
element &&
element.tagName
?.toLowerCase() === 'select'
) {
const option =
element.selectedOptions?.[0];

emit({
...baseEvent(
control,
'SELECT',
'SELECT'
),
value: {
raw:
clean(element.value),
selectedValue:
clean(element.value),
selectedText:
clean(option?.textContent)
}
});

return;
}

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

document.addEventListener(
'focusout',
event => {
const control =
resolveControl(event);

flushEditable(control);
},
true
);

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

if (
control.customHost &&
hasControlBinding(
control.customHost
)
) {
const before =
currentValue(control);

const activeControl =
activateLogicalControl(
control
);

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
activeControl || control,
before
);
}

return;
}

if (
activeLogicalControl &&
(
role === 'option' ||
role === 'gridcell' ||
role === 'menuitem' ||
role === 'treeitem' ||
role === 'radio' ||
clicked
?.getAttribute
?.('aria-selected') != null ||
isOverlayElement(clicked)
)
) {
const clickedCheckbox =
clicked.matches?.(
'input[type="checkbox"],' +
'[role="checkbox"],' +
'[role="menuitemcheckbox"]'
) ||
clicked.querySelector?.(
'input[type="checkbox"],' +
'[role="checkbox"],' +
'[role="menuitemcheckbox"]'
);

if (clickedCheckbox) {
return;
}

const owner =
activeLogicalControl;

const before =
currentValue(owner);

const selectedText =
optionText(clicked);

const selectedValue =
optionValue(clicked);

const state =
selectionState(owner);

setTimeout(
() => {
const actual =
currentValue(owner);

emitSelection(
owner,
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
owner,
before
);

return;
}

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

if (
type === 'CHECKBOX' ||
type === 'RADIO' ||
type === 'TOGGLE'
) {
return;
}

if (
role === 'option' ||
type === 'OPTION'
) {
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

document.addEventListener(
'keydown',
event => {
if (
![
'Enter',
'Tab',
'Escape'
].includes(event.key)
) {
return;
}

const control =
resolveControl(event);

emit({
...baseEvent(
control,
'KEY'
),
value: {
raw: event.key
}
});
},
true
);

function checkStageChange() {
setTimeout(
() => {
if (location.href !== lastUrl) {
lastUrl =
location.href;

currentStage++;
}
},
0
);
}

const originalPushState =
history.pushState.bind(history);

history.pushState =
function (...args) {
const result =
originalPushState(...args);

checkStageChange();

return result;
};

const originalReplaceState =
history.replaceState.bind(history);

history.replaceState =
function (...args) {
const result =
originalReplaceState(...args);

checkStageChange();

return result;
};

window.addEventListener(
'popstate',
checkStageChange,
true
);

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
childList: true,
subtree: true
}
);

})();