package com.example.angularrecorder.service;

import com.example.angularrecorder.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlaywrightAngularRecorder implements AutoCloseable {
    private static final String PREFIX="__ANGULAR_RECORDER_EVENT__";
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<JsonNode> rawEvents=new CopyOnWriteArrayList<>();
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    public void open(String url){
        playwright=Playwright.create();
        browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
        context=browser.newContext();
        context.addInitScript(RECORDING_SCRIPT);
        page=context.newPage();
        page.onConsoleMessage(m->{
            String s=m.text();
            if(s==null||!s.startsWith(PREFIX))return;
            try{
                JsonNode n=mapper.readTree(s.substring(PREFIX.length()));
                rawEvents.add(n);
                System.out.println(PREFIX+mapper.writeValueAsString(n));
            }catch(Exception e){System.err.println("RECORDER_PARSE_ERROR:"+e.getMessage());}
        });
        page.navigate(url);
        page.waitForLoadState();
    }

    public List<RecordedEvent> readEvents(){
        mergeFallbackEvents();
        List<JsonNode> nodes=dedupe(rawEvents);
        List<RecordedEvent> out=new ArrayList<>(nodes.size());
        long seq=1;
        for(JsonNode n:nodes)out.add(toEvent(seq++,n));
        return out;
    }

    private List<JsonNode> dedupe(List<JsonNode> events){
        List<JsonNode> sorted=new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(n->n.path("timestamp").asLong(0)));
        List<JsonNode> out=new ArrayList<>();
        Map<String,Long> seen=new HashMap<>();
        Set<String> ids=new HashSet<>();
        for(JsonNode n:sorted){
            String id=text(n,"eventId");
            if(id!=null&&!ids.add(id))continue;
            String key=text(n,"stage")+"|"+text(n,"actionType")+"|"+text(n,"inputType")+"|"+text(n,"input")+"|"+valuePreview(n);
            long ts=n.path("timestamp").asLong(0);
            Long last=seen.get(key);
            if(last!=null&&ts-last<1200)continue;
            seen.put(key,ts);
            out.add(n);
        }
        return out;
    }

    private void mergeFallbackEvents(){
        if(page==null||page.isClosed())return;
        try{
            JsonNode arr=mapper.valueToTree(page.evaluate("() => window.__angularRecorderEvents || []"));
            if(!arr.isArray())return;
            Set<String> ids=new HashSet<>();
            for(JsonNode n:rawEvents){String id=text(n,"eventId");if(id!=null)ids.add(id);}
            for(JsonNode n:arr){String id=text(n,"eventId");if(id==null||ids.add(id))rawEvents.add(n);}
        }catch(Exception ignored){}
    }

    private RecordedEvent toEvent(long sequence,JsonNode node){
        IdentifyBy identifyBy=null;
        JsonNode i=node.get("identifyBy");
        if(i!=null&&!i.isNull()){
            Map<String,String> c=new LinkedHashMap<>();
            JsonNode cn=i.get("candidates");
            if(cn!=null&&cn.isObject())cn.fields().forEachRemaining(e->{String v=e.getValue().asText("");if(!v.isBlank())c.put(e.getKey(),v);});
            identifyBy=new IdentifyBy(text(i,"preferredStrategy"),text(i,"preferredValue"),c);
        }
        RecordedValue value=null;
        JsonNode v=node.get("value");
        if(v!=null&&!v.isNull()){
            if(v.isTextual())value=new RecordedValue(v.asText(),null,null,null,null,null);
            else value=new RecordedValue(text(v,"raw"),text(v,"searchText"),text(v,"selectedValue"),text(v,"selectedText"),text(v,"fileName"),bool(v,"checked"));
        }
        ContextInfo contextInfo=null;
        JsonNode c=node.get("context");
        if(c!=null&&!c.isNull())contextInfo=new ContextInfo(text(c,"type"),text(c,"name"));
        WaitInfo waitInfo=null;
        JsonNode w=node.get("wait");
        if(w!=null&&!w.isNull())waitInfo=new WaitInfo(text(w,"type"),text(w,"target"),text(w,"role"));
        return new RecordedEvent(sequence,node.path("timestamp").asLong(System.currentTimeMillis()),node.path("stage").asLong(1),text(node,"actionType"),text(node,"inputType"),text(node,"input"),identifyBy,value,contextInfo,waitInfo,text(node,"url"));
    }

    private String valuePreview(JsonNode n){
        JsonNode v=n.get("value");
        if(v==null||v.isNull())return "";
        if(v.isTextual())return v.asText();
        for(String f:new String[]{"raw","selectedValue","selectedText","searchText","fileName"}){String x=text(v,f);if(x!=null)return x;}
        return v.has("checked")?String.valueOf(v.path("checked").asBoolean()):"";
    }
    private String text(JsonNode n,String name){if(n==null)return null;JsonNode c=n.get(name);if(c==null||c.isNull())return null;String v=c.asText();return v.isBlank()?null:v;}
    private Boolean bool(JsonNode n,String name){return n!=null&&n.has(name)&&!n.get(name).isNull()?n.get(name).asBoolean():null;}

    @Override public void close(){
        if(context!=null)context.close();
        if(browser!=null)browser.close();
        if(playwright!=null)playwright.close();
    }

    private static final String RECORDING_SCRIPT="""
(()=>{if(window.__angularRecorderInstalled)return;window.__angularRecorderInstalled=true;window.__angularRecorderEvents=[];const P='__ANGULAR_RECORDER_EVENT__',C=v=>{if(v==null)return null;v=String(v).replace(/\\s+/g,' ').trim();return v||null},A=e=>e&&e.getAttribute?e.getAttribute.bind(e):()=>null;let stage=1,lastUrl=location.href,active=null,counter=0;const timers=new WeakMap(),states=new WeakMap(),lastEmit=new WeakMap();
const emit=e=>{const now=Date.now(),x={eventId:now+'-'+(++counter),timestamp:now,stage,url:location.href,...e};window.__angularRecorderEvents.push(x);console.log(P+JSON.stringify(x));};
const path=e=>(e.composedPath?e.composedPath():[]).filter(x=>x&&x.nodeType===1),anc=e=>{const a=[];for(let x=e;x&&x!==document.documentElement;x=x.parentElement)a.push(x);return a},bound=e=>!!(e&&e.getAttribute&&(e.hasAttribute('formControlName')||e.hasAttribute('formcontrolname')||e.hasAttribute('controlName')||e.hasAttribute('controlname')||e.hasAttribute('ngModel')||e.hasAttribute('ngmodel')||e.hasAttribute('ng-reflect-name'))),custom=e=>!!(e&&e.tagName&&e.tagName.includes('-'));
const native=e=>path(e).find(x=>x.matches?.('input,textarea,select,button,a,[contenteditable="true"],[role="textbox"],[role="searchbox"],[role="combobox"],[role="checkbox"],[role="radio"],[role="switch"],[role="button"],[role="link"],[role="option"],[role="menuitem"],[role="treeitem"],[role="gridcell"]'))||e.target;
const host=(el,e)=>{const a=[...path(e),...anc(el)];return a.find(bound)||a.find(x=>x!==el&&custom(x))||null};
const label=(el,h)=>{const by=x=>{if(!x)return null;if(x.labels?.length)return C([...x.labels].map(l=>l.textContent).join(' '));let id=C(x.id);if(id){try{let l=document.querySelector('label[for="'+CSS.escape(id)+'"]');if(l)return C(l.textContent)}catch(_){}}let ids=C(A(x)('aria-labelledby'));if(ids)return C(ids.split(/\\s+/).map(i=>document.getElementById(i)?.textContent||'').join(' '));return C(A(x)('aria-label'))};return by(el)||by(h)||C((h||el)?.closest?.('.form-group,.form-field,.field,[class*="form-group"],[class*="form-field"]')?.querySelector?.('label')?.textContent)};
const name=(el,h)=>C(A(el)('formControlName')||A(el)('formcontrolname')||A(h)('formControlName')||A(h)('formcontrolname')||A(el)('controlName')||A(el)('controlname')||A(h)('controlName')||A(h)('controlname')||A(el)('name')||A(h)('name')||A(el)('ng-reflect-name')||A(h)('ng-reflect-name'));
const resolve=e=>{const el=native(e),h=host(el,e);return{nativeElement:el,clickedElement:path(e)[0]||e.target,customHost:h,label:label(el,h),controlName:name(el,h)}};
const candidates=c=>{const r={},e=c.nativeElement,h=c.customHost,l=c.label,n=c.controlName;if(l)r.LABEL=l;let a=C(A(e)('aria-label')||A(h)('aria-label'));if(a)r.ARIA_LABEL=a;if(n){let f=C(A(e)('formControlName')||A(e)('formcontrolname')||A(h)('formControlName')||A(h)('formcontrolname'));r[f?'FORM_CONTROL_NAME':'CONTROL_NAME']=f||n}let nm=C(A(e)('name')||A(h)('name')),id=C(e?.id||h?.id),role=C(A(e)('role')||A(h)('role')),test=C(A(e)('data-testid')||A(h)('data-testid'));if(nm)r.NAME=nm;if(id)r.ID=id;if(role)r.ROLE=role;if(test)r.DATA_TESTID=test;if(h?.tagName)r.COMPONENT=h.tagName.toLowerCase();return r};
const identify=c=>{const x=candidates(c);for(const k of ['LABEL','ARIA_LABEL','FORM_CONTROL_NAME','CONTROL_NAME','NAME','ID','DATA_TESTID','ROLE','COMPONENT'])if(x[k])return{preferredStrategy:k,preferredValue:x[k],candidates:x};return{preferredStrategy:'INPUT',preferredValue:c.label||c.controlName||'unnamed',candidates:x}};
const type=c=>{const e=c.nativeElement,h=c.customHost,t=C(A(e)('type'))?.toLowerCase(),tag=(e?.tagName||'').toLowerCase(),r=C(A(e)('role')||A(h)('role'))?.toLowerCase();if(t==='file')return'FILE';if(t==='checkbox'||r==='checkbox')return'CHECKBOX';if(t==='radio'||r==='radio')return'RADIO';if(r==='switch')return'TOGGLE';if(['date','datetime-local','month','week','time'].includes(t)||h?.querySelector?.('input[type="date"],input[type="datetime-local"],input[type="month"],input[type="week"],input[type="time"]'))return'DATE';if(tag==='select')return'SELECT';if(tag==='textarea')return'TEXTAREA';if(r==='combobox'||r==='searchbox'||A(e)('aria-autocomplete')!=null||h?.querySelector?.('[role="combobox"],[role="searchbox"],input[aria-autocomplete]'))return'AUTOCOMPLETE';if(t==='password')return'PASSWORD';if(t==='email')return'EMAIL';if(t==='number')return'NUMBER';if(t==='tel')return'TEL';if(t==='url')return'URL';if(t==='search')return'SEARCH';if(tag==='input'||r==='textbox')return'TEXT';if(tag==='button'||r==='button')return'BUTTON';if(tag==='a'||r==='link')return'LINK';if(['option','menuitem','treeitem','gridcell'].includes(r))return'OPTION';return h?'CUSTOM':'CLICK'};
const inputName=c=>c.label||c.controlName||identify(c).preferredValue;
const snapshot=c=>({...c,recorderIdentity:{input:inputName(c),identifyBy:identify(c),inputType:type(c)}}),key=c=>c?.customHost||c?.nativeElement||null,activate=c=>active=snapshot(c);
const ev=(c,a,t)=>{const s=c?.recorderIdentity;return{actionType:a,inputType:t||s?.inputType||type(c),input:s?.input||inputName(c),identifyBy:s?.identifyBy||identify(c)}};
const val=e=>{if(!e)return null;let t=C(A(e)('type'))?.toLowerCase();if((t==='checkbox'||t==='radio')&&'checked'in e&&!e.checked)return null;if(e.tagName?.toLowerCase()==='select')return C(e.value||e.selectedOptions?.[0]?.textContent);if(e.isContentEditable)return C(e.innerText);if('value'in e&&C(e.value))return C(e.value);return C(A(e)('aria-valuetext')||A(e)('aria-valuenow')||A(e)('data-value')||A(e)('ng-reflect-model'))};
const current=c=>{if(!c)return null;const a=[],push=x=>x&&!a.includes(x)&&a.push(x);push(c.nativeElement);c.customHost?.querySelectorAll?.('input:not([type="button"]):not([type="submit"]),textarea,select,[contenteditable="true"],[role="textbox"],[role="combobox"]')?.forEach(push);for(const e of a){if(A(e)('role')==='searchbox'||A(e)('type')==='search')continue;let v=val(e);if(v)return v}let h=c.customHost,v=C(A(h)('value')||A(h)('data-value')||A(h)('ng-reflect-model'));if(v)return v;return C(h?.querySelector?.('[aria-selected="true"],[data-selected="true"],.selected-value,[class*="selected-value"]')?.textContent)};
const state=c=>{let k=key(c);if(!k)return null;let s=states.get(k);if(!s){s={control:c,searchText:null,selectedValues:new Set(),selectedTexts:new Set()};states.set(k,s)}s.control=c;return s};
const logicalEmit=(c,v,a)=>{v=C(v);if(!c||!v)return;let k=key(c),p=lastEmit.get(k),now=Date.now();if(p&&p.v===v&&p.a===a&&now-p.t<1200)return;lastEmit.set(k,{v,a,t:now});emit({...ev(c,a||'INPUT'),value:{raw:v}})};
const selection=(c,raw,sv,st,a)=>{let x=C(raw)||C(sv)||C(st);if(!c||!x)return;let k=key(c),p=lastEmit.get(k),now=Date.now();if(p&&p.v===x&&now-p.t<1200)return;lastEmit.set(k,{v:x,a,t:now});let s=state(c);emit({...ev(c,a||'SELECT'),value:{raw:x,searchText:s?.searchText||null,selectedValue:C(sv)||x,selectedText:C(st)||x}});if(s)s.searchText=null};
const overlay=e=>!!e?.closest?.('[role="listbox"],[role="grid"],[role="menu"],[role="tree"],.cdk-overlay-pane,.cdk-overlay-container,[class*="overlay"],[class*="dropdown"],[class*="popup"]'),opt=e=>C(A(e)('aria-label')||A(e)('data-label')||A(e)('value')||A(e)('data-value')||e?.textContent);
const wait=(c,b,n=0)=>{if(!c||n>8)return;setTimeout(()=>{let a=current(c);if(a&&a!==b){selection(c,a,a,a,state(c)?.searchText?'SEARCH_AND_SELECT':'SELECT');return}wait(c,b,n+1)},n<3?80:160)};
const schedule=c=>{let e=c.nativeElement;if(!e)return;clearTimeout(timers.get(e));timers.set(e,setTimeout(()=>{logicalEmit(c,current(c),type(c)==='DATE'?'DATE_INPUT':'INPUT');timers.delete(e)},350))};
document.addEventListener('focusin',e=>{let c=resolve(e);if(c.customHost&&bound(c.customHost))activate(c)},true);
document.addEventListener('input',e=>{let c=resolve(e),el=c.clickedElement||c.nativeElement;if(c.customHost&&bound(c.customHost)&&(A(el)('role')==='searchbox'||A(el)('type')==='search'||A(el)('aria-autocomplete')!=null)){let a=activate(c),s=state(a);if(s)s.searchText=C(el.value);return}if(!['CHECKBOX','RADIO','TOGGLE','FILE','SELECT'].includes(type(c)))schedule(c)},true);
document.addEventListener('change',e=>{let c=resolve(e),el=c.nativeElement,t=type(c);if(t==='FILE'){emit({...ev(c,'FILE_UPLOAD','FILE'),value:{fileName:[...(el.files||[])].map(f=>f.name).join(', ')||null}});return}if(t==='CHECKBOX'||t==='TOGGLE'){let checked='checked'in el?!!el.checked:A(el)('aria-checked')==='true';emit({...ev(c,checked?'CHECK':'UNCHECK',t),value:{raw:current(c),checked}});return}if(t==='RADIO'){let checked='checked'in el?!!el.checked:A(el)('aria-checked')==='true';if(checked)emit({...ev(c,'RADIO','RADIO'),value:{raw:val(el),selectedValue:val(el),selectedText:inputName(c),checked:true}});return}if(el?.tagName?.toLowerCase()==='select'){let o=el.selectedOptions?.[0];selection(c,el.value,el.value,o?.textContent,'SELECT');return}logicalEmit(c,current(c),t==='DATE'?'DATE_INPUT':'INPUT')},true);
document.addEventListener('click',e=>{let c=resolve(e),el=c.clickedElement||c.nativeElement,t=type(c),r=C(A(el)('role'));if(c.customHost&&bound(c.customHost)){let b=current(c),a=activate(c);if(!['TEXT','PASSWORD','EMAIL','NUMBER','TEL','URL','SEARCH','TEXTAREA'].includes(t))wait(a,b);return}if(active&&(overlay(el)||['option','gridcell','menuitem','treeitem'].includes(r))){if(el.matches?.('input[type="checkbox"],[role="checkbox"]')||el.querySelector?.('input[type="checkbox"],[role="checkbox"]'))return;let owner=active,b=current(owner),o=opt(el),s=state(owner);setTimeout(()=>selection(owner,current(owner)||o,o,o,s?.searchText?'SEARCH_AND_SELECT':'SELECT'),100);wait(owner,b);return}if(['TEXT','PASSWORD','EMAIL','NUMBER','TEL','URL','SEARCH','TEXTAREA','AUTOCOMPLETE','CHECKBOX','RADIO','TOGGLE'].includes(t))return;if(r==='option'||t==='OPTION'){let o=opt(el);selection(c,o,o,o,'SELECT');return}emit({...ev(c,A(el)('aria-haspopup')||A(el)('aria-controls')?'OPEN_POPUP':'CLICK'),value:{raw:C(el?.textContent)||inputName(c)}})},true);
document.addEventListener('keydown',e=>{if(['Enter','Tab','Escape'].includes(e.key)){let c=resolve(e);emit({...ev(c,'KEY'),value:{raw:e.key}})}},true);
const stageCheck=()=>setTimeout(()=>{if(location.href!==lastUrl){lastUrl=location.href;stage++}},0),ps=history.pushState.bind(history),rs=history.replaceState.bind(history);history.pushState=(...a)=>{let r=ps(...a);stageCheck();return r};history.replaceState=(...a)=>{let r=rs(...a);stageCheck();return r};addEventListener('popstate',stageCheck,true);
})();
""";
}
