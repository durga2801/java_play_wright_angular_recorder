(() => {
if(window.__angularRecorderInstalled)return;
window.__angularRecorderInstalled=true;
window.__angularRecorderEvents=[];
const PREFIX="__ANGULAR_RECORDER_EVENT__";
const timers=new WeakMap();
const pending=new WeakMap();
const lastLogicalEmissions=new WeakMap();
const selectionStates=new WeakMap();
const multiSelectTimers=new WeakMap();
let currentStage=1;
let lastUrl=location.href;
let activeLogicalControl=null;
let eventCounter=0;

function clean(v){
    if(v==null)return null;
    const s=String(v).replace(/\s+/g," ").trim();
    return s||null;
}

function shortText(e){
    if(!e)return null;
    const s=clean(e.innerText||e.textContent);
    return s&&s.length<=180?s:null;
}

function eventElement(e){
    const path=e?.composedPath?.()||[];
    return path.find(x=>x?.nodeType===Node.ELEMENT_NODE)||e?.target||null;
}

function eventPath(e){
    return (e?.composedPath?.()||[])
        .filter(x=>x?.nodeType===Node.ELEMENT_NODE);
}

function ancestors(e){
    const a=[];
    while(e&&e!==document.documentElement){
        a.push(e);
        e=e.parentElement;
    }
    return a;
}

function isNativeOrSemanticControl(e){
    return !!e?.matches?.(
        'input,textarea,select,button,a,'+
        '[contenteditable="true"],[role="textbox"],[role="searchbox"],'+
        '[role="combobox"],[role="checkbox"],[role="radio"],[role="switch"],'+
        '[role="button"],[role="link"],[role="option"],[role="menuitem"],'+
        '[role="slider"],[role="spinbutton"]'
    );
}

function isLogicalAngularHost(e){
    if(!e?.getAttribute)return false;
    const tag=(e.tagName||"").toLowerCase();
    return tag.includes("-")||
        e.hasAttribute("formControlName")||
        e.hasAttribute("formcontrolname")||
        e.hasAttribute("controlName")||
        e.hasAttribute("controlname")||
        e.hasAttribute("ngModel")||
        e.hasAttribute("ngmodel");
}

function hasControlBinding(e){
    if(!e?.getAttribute)return false;
    return e.hasAttribute("formControlName")||
        e.hasAttribute("formcontrolname")||
        e.hasAttribute("controlName")||
        e.hasAttribute("controlname")||
        e.hasAttribute("ngModel")||
        e.hasAttribute("ngmodel")||
        e.hasAttribute("ng-reflect-name");
}

function closestMeaningful(event){
    const path=eventPath(event);
    for(const n of path)if(isNativeOrSemanticControl(n))return n;
    for(const n of path)if(isLogicalAngularHost(n))return n;
    return eventElement(event);
}

function findLogicalAngularHost(nativeElement,event){
    const nodes=[...eventPath(event),...ancestors(nativeElement)];
    for(const n of nodes)if(hasControlBinding(n))return n;
    for(const n of nodes){
        if(n!==nativeElement&&isLogicalAngularHost(n))return n;
    }
    return null;
}

function controlName(nativeElement,host){
    for(const e of [nativeElement,host]){
        if(!e?.getAttribute)continue;
        const v=clean(
            e.getAttribute("formControlName")||
            e.getAttribute("formcontrolname")||
            e.getAttribute("controlName")||
            e.getAttribute("controlname")||
            e.getAttribute("name")||
            e.getAttribute("ng-reflect-name")
        );
        if(v)return v;
    }
    return null;
}

function labelledBy(e){
    if(!e?.getAttribute)return null;
    const ids=clean(e.getAttribute("aria-labelledby"));
    if(!ids)return null;
    return clean(
        ids.split(/\s+/)
            .map(id=>document.getElementById(id))
            .filter(Boolean)
            .map(x=>x.innerText||x.textContent)
            .join(" ")
    );
}

function cleanLabelText(label){
    if(!label)return null;
    const c=label.cloneNode(true);
    c.querySelectorAll(
        '[role="tooltip"],[class*="tooltip"],[class*="error"],mat-icon,svg,small'
    ).forEach(x=>x.remove());
    return clean(c.textContent);
}

function associatedLabel(e){
    if(!e)return null;

    if(e.labels?.length){
        const v=clean(Array.from(e.labels)
            .map(cleanLabelText)
            .filter(Boolean)
            .join(" "));
        if(v)return v;
    }

    const id=clean(e.id);
    if(id){
        try{
            const l=document.querySelector(`label[for="${CSS.escape(id)}"]`);
            if(l)return cleanLabelText(l);
        }catch(_){}
    }

    const wrapping=e.closest?.("label");
    if(wrapping){
        const v=cleanLabelText(wrapping);
        if(v)return v;
    }

    return labelledBy(e);
}