const $=s=>document.querySelector(s), content=$('#content'), feature=$('#feature'), message=$('#message');
const params=new URLSearchParams(location.search), mobile=params.get('mobile')==='1';
document.body.classList.toggle('mobile',mobile);
let view=mobile?'pc':'design', info, f, state, graph, selected='start', replay=null,dirty=false,loading=false,epoch=0;
const inputCache=new Map();
const names={start:'开始',retrieve:'知识检索',model:'调用大模型',tool:'执行业务工具',check:'校验与分支',review:'人工确认',store:'存储结果'};
const kinds={start:'用户输入 / 会话记忆',retrieve:'文档分块 / 向量检索',model:'Spring AI · ChatModel',tool:'Spring AI · ToolCallback',check:'通过 / 回到模型修订',review:'等待操作人决定',store:'H2 · 运行账本'};
const places={start:[20,30],retrieve:[215,30],model:[435,30],tool:[625,148],check:[435,270],review:[215,380],store:[20,270]};
const states={completed:'已完成',waiting:'等待确认',failed:'失败',interrupted:'中断',model_unavailable:'模型未配置',budget_limit:'预算已达上限',provider_error:'供应商响应未确认',usage_unknown:'用量未知',limit:'达到轮数上限',rejected:'已退回',running:'执行中'};
function el(tag,txt,cls){let n=document.createElement(tag);if(txt!==undefined)n.textContent=txt;if(cls)n.className=cls;return n}
function btn(title,fn,cls){let b=el('button',title,cls);b.onclick=()=>guard(fn);return b}
function say(t){message.textContent=t}
async function guard(fn){try{await fn()}catch(e){say(e.message)}}
async function api(path,body){const res=await fetch('api/'+path,{method:body===undefined?'GET':'POST',headers:body===undefined?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});const data=await res.json();if(!res.ok)throw Error(data.error||'请求未完成');return data}
function choose(items,value){const s=el('select');for(const [id,title] of items){const o=el('option',title);o.value=id;s.append(o)}s.value=value;return s}
function field(host,name,control){const l=el('label',name);control.setAttribute('aria-label',name);host.append(l,control)}
function j(v){return JSON.stringify(v,null,2)}
async function fresh(){
 const e=++epoch,app=f.id;
 content.replaceChildren(el('p','正在加载流程…'));delete content.dataset.app;
 document.querySelectorAll('nav button').forEach(b=>b.disabled=true);
 try{const data=await api(app);if(e!==epoch)return;state=data;graph=structuredClone(data.draft);dirty=false;replay=null;render();content.dataset.app=app}
 finally{if(e===epoch)document.querySelectorAll('nav button').forEach(b=>b.disabled=false)}
}
function go(v){if(loading)return;view=v;replay=null;render()}
const w=n=>document.createElementNS('http://www.w3.org/2000/svg',n);
function attr(n,attrs){for(const [k,v] of Object.entries(attrs))n.setAttribute(k,v);return n}
function draw(host,g,locked){
 const surface=el('div',undefined,'surface'),stage=el('div',undefined,'stage'),aside=el('div',undefined,'inspect');host.append(surface,aside);surface.append(stage);
 const svg=w('svg');stage.append(svg);const nodes={};const pos=id=>g.positions[id]||places[id];
 function lines(){
  const width=Math.max(810,...Object.keys(names).map(id=>pos(id)[0]+166)),height=Math.max(440,...Object.keys(names).map(id=>pos(id)[1]+85));stage.style.width=width+'px';stage.style.height=height+'px';
  attr(svg,{width,height});svg.replaceChildren();const defs=w('defs');svg.append(defs);
  for(const [id,col] of [['plain','#899da6'],['used','#127e69']]){const m=attr(w('marker'),{id,viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:7,markerHeight:7,orient:'auto'});m.append(attr(w('path'),{d:'M0 0L10 5L0 10z',fill:col}));defs.append(m)}
  for(const [edge,to] of Object.entries(g.edges)){
   const from=edge.split('.')[0],[x,y]=pos(from),[u,v]=pos(to);const active=locked&&replay.trace.some(t=>t.edge===edge||from==='review'&&t.decision==='approve'&&t.next===to);
   const vertical=Math.abs(v-y)>85, sx=vertical?x+73:x+(u>x?146:0),sy=vertical?y+(v>y?62:0):y+31,ex=vertical?u+73:u+(u>x?0:146),ey=vertical?v+(v>y?0:62):v+31;
   const bend=(sx+ex)/2,mid=(sy+ey)/2;
   const path=attr(w('path'),{d:vertical?'M'+sx+' '+sy+' C'+sx+' '+mid+' '+ex+' '+mid+' '+ex+' '+ey:'M'+sx+' '+sy+' C'+bend+' '+sy+' '+bend+' '+ey+' '+ex+' '+ey,fill:'none',stroke:active?'#127e69':'#899da6','stroke-width':active?3:1.7,'marker-end':'url(#'+(active?'used':'plain')+')'});svg.append(path);
   if(from==='check'){const t=attr(w('text'),{x:bend+3,y:mid-4,fill:'#31576a','font-size':12});t.textContent=edge.endsWith('pass')?'通过':'修订';svg.append(t)}
  }
  for(const [id,n] of Object.entries(nodes)){n.style.left=pos(id)[0]+'px';n.style.top=pos(id)[1]+'px';n.classList.toggle('selected',id===selected)}
 }
 for(const id of Object.keys(names)){
  const n=el('button',undefined,'node');n.setAttribute('aria-label',names[id]);n.append(el('b',names[id]),el('small',kinds[id]));nodes[id]=n;stage.append(n);
  if(locked&&replay.trace.some(t=>t.node===id))n.classList.add('hit');
  n.onclick=()=>{selected=id;lines();inspect()};
  if(!locked)n.onpointerdown=e=>{if(e.button!==0)return;const [x,y]=pos(id),ax=e.clientX,ay=e.clientY;n.setPointerCapture(e.pointerId);
   n.onpointermove=t=>{if(Math.abs(t.clientX-ax)+Math.abs(t.clientY-ay)<5)return;g.positions[id]=[Math.round(Math.min(1500,Math.max(0,x+t.clientX-ax))),Math.round(Math.min(1100,Math.max(0,y+t.clientY-ay)))];changed();lines()};
   n.onpointerup=n.onpointercancel=()=>{n.onpointermove=null};
  };
 }
 function changed(){dirty=true;const s=$('#draft-state');if(s)s.textContent='有未保存修改'}
 function inspect(){
  aside.replaceChildren(el('h2',names[selected]),el('small',kinds[selected]));
  if(locked){
   const events=replay.trace.filter(t=>t.node===selected);aside.append(el('p',events.length?'本次经过 '+events.length+' 次':'本次未经过此节点'));
   for(const e of events){
    aside.append(el('h3','步骤 '+e.seq+(e.elapsedMs!==undefined?' · '+e.elapsedMs+' ms':'')));
    if(e.input)aside.append(el('small','本次输入'),el('pre',e.input));
    if(e.index){aside.append(el('p',e.index+(e.reusedIndex===true?' · 复用已存索引':'')));
     for(const hit of e.hits||[])aside.append(el('b',hit.source+' · 块 '+hit.chunk+' · '+hit.score.toFixed(3)),el('p',hit.text));}
    if(e.model){aside.append(el('b',e.model),el('p','第 '+e.round+' 轮 · 输入 '+(e.inputTokens??'未知')+' / 输出 '+(e.outputTokens??'未知')+' token'));
     if(e.reply)aside.append(el('small','AI 生成 · 模型返回'),el('pre',e.reply));
     if(e.requestedTools)aside.append(el('small','模型请求的工具与参数'),el('pre',j(e.requestedTools)));
     if(e.estimatedCny!=null)aside.append(el('p','本轮估算 ¥'+e.estimatedCny));}
    if(e.tool)aside.append(el('b',e.tool),el('p','业务规则 '+e.rule+' · 输入绑定 '+e.binding));
    if(e.output)aside.append(el('pre',j(e.output)));
    if(e.reason||e.validation)aside.append(el('p',e.reason||e.validation));
    if(e.record){aside.append(el('small','已存运行编号'),el('pre',e.record));if(replay.stored)aside.append(el('b','结果集 '+replay.stored.collection),el('pre',j(replay.stored.value)));}
    if(e.next)aside.append(el('p','下一节点 → '+(names[e.next]||e.next)));
    const details=el('details');details.append(el('summary','完整调用记录'),el('pre',j(e)));aside.append(details);
   }return;
  }
  function edit(key,title,type='text',min,max){let c=el(type==='area'?'textarea':'input');if(type!=='area')c.type=type;if(min!==undefined)c.min=min;if(max!==undefined)c.max=max;if(key==='budget'||key==='threshold')c.step='any';c.value=g[key];c.oninput=()=>{g[key]=type==='number'?Number(c.value):c.value;changed()};field(aside,title,c)}
  if(selected==='start'){let c=choose([['no','仅本次输入'],['yes','读取上次已完成结果摘要']],g.memory?'yes':'no');c.onchange=()=>{g.memory=c.value==='yes';changed()};field(aside,'输入上下文',c)}
  if(selected==='retrieve'){const r=choose([['lexical','本地词频向量'],['dense','Embedding 模型向量（H2 缓存）']],g.retrieval??'lexical');r.onchange=()=>{g.retrieval=r.value;changed()};field(aside,'检索索引',r);edit('library','知识文档，每行「来源 | 正文」','area');edit('chunkSize','每块字符数','number',40,600);edit('topK','召回条数','number',1,5);edit('threshold','最低余弦相似度','number',0,1);aside.append(el('small','按已发布工作区文档检索；命中原文、来源、分数进入模型上下文。'))}
  if(selected==='model'){aside.append(el('p',(info.ready?'可调用 · ':'未连接 · ')+(info.model||'模型由服务端配置')));const n=choose([['no','文本 → 图工具节点'],['yes','模型发起函数调用']],g.nativeTool?'yes':'no');n.onchange=()=>{g.nativeTool=n.value==='yes';changed()};field(aside,'工具调用模式',n);edit('prompt','系统提示词','area');edit('maxTokens','每次最大输出 token','number',16,512);edit('maxCalls','每次运行最多模型轮数','number',1,5);edit('budget','本次预算（元）','number',0,5)}
  if(selected==='tool'){const binding=choose([['auto','优先保留原始结构化字段'],['upstream','上游输出'],['input','开始节点原始输入']],g.binding??'auto');binding.onchange=()=>{g.binding=binding.value;changed()};field(aside,'工具输入绑定',binding);const c=choose(f.options.map(o=>[o.key,o.label]),g.rule);c.onchange=()=>{g.rule=c.value;changed()};field(aside,f.rule,c);aside.append(el('pre',f.engine),el('small','输入取自上游文本，返回结构化业务结果。'))}
  if(selected==='check')aside.append(el('p','工具输入无效或标题有错误时走修订分支，上限取自模型节点。'));
  if(selected==='store'){
    g.collection??=f.id+'-results';edit('collection','业务结果集名称');
    const c=choose([['full','完整业务结果'],['summary','仅结果摘要']],g.projection??'full');c.onchange=()=>{g.projection=c.value;changed()};field(aside,'保存到结果集的字段',c);
    aside.append(el('small','结果集按工作区隔离，与完成状态在同一事务中写入。完整调用轨迹单独保存供回放。'));
  }
  for(const [port,allowed] of Object.entries(info.ports).filter(([k])=>k.startsWith(selected+'.'))){const c=choose(allowed.map(id=>[id,names[id]]),g.edges[port]);c.onchange=()=>{g.edges[port]=c.value;changed();lines()};field(aside,({next:'下一步',pass:'校验通过',fail:'校验未通过',approve:'确认后'})[port.split('.')[1]],c)}
 }
 lines();inspect();
}
async function save(){state=await api(f.id+'/draft',{revision:state.revision,graph});dirty=false;render();say('草稿已保存')}
function design(){
 const head=el('div',undefined,'head'),label=el('small',dirty?'有未保存修改':'草稿 r'+state.revision+' · 已发布 v'+state.version);label.id='draft-state';
 const a=el('div',undefined,'actions');a.append(btn('保存草稿',save),btn('发布流程',async()=>{if(dirty)await save();state=await api(f.id+'/publish',{revision:state.revision});render();say('已发布 v'+state.version)},'primary'));head.append(label,a);content.append(head);
 const grid=el('div',undefined,'grid');content.append(grid);draw(grid,graph,false);
}
function evidence(parent,run){
 parent.replaceChildren();parent.append(el('h3',(states[run.status]||run.status)+' · v'+run.version),el('small','运行编号 '+run.id));
 if(run.result){parent.append(el('p',run.result.summary));const t=el('table');for(const row of [run.result.columns,...run.result.rows]){const tr=el('tr');row.forEach(c=>tr.append(el('td',c)));t.append(tr)}parent.append(t)}
 const llm=run.trace.filter(e=>e.node==='model'&&e.round);
 if(llm.some(e=>e.reply||e.requestedTools?.length))parent.append(el('small','本次流程使用 AI 生成的内容或工具参数，请核对结果。'));
 parent.append(el('p','模型尝试 '+llm.length+' 次 · '+(run.ledger.length?'用量账本 '+run.ledger.map(v=>v.status==='estimated'?'¥'+v.amount+' 估算':'待核对').join(' / '):'无调用费用记录')));
 if(run.status==='waiting'){for(const [ok,text] of [[true,'确认保存'],[false,'退回']])parent.append(btn(text,async()=>{const next=await api(f.id+'/confirm',{id:run.id,approve:ok});evidence(parent,next)}))}
 parent.append(btn('查看执行画布',()=>{view='history';replay=run;selected=run.trace.at(-1)?.node||'start';render()}));
}
async function submitRun(app,version,input){
 const id=crypto.randomUUID(),path=app+'/runs/'+id;
 let record;
 try{record=await api(app+'/run',{id,version,input})}
 catch(error){try{record=await api(path)}catch{throw Error(error.message+' · 运行编号 '+id+'，可在运行记录中核对')}}
 const until=Date.now()+480000;
 while(record.status==='running'&&Date.now()<until){
  say('后端正在执行 · '+id);
  await new Promise(done=>setTimeout(done,1200));record=await api(path);
 }
 return record;
}
function pc(){
 const box=el('section',undefined,'box'),input=el('textarea');input.value=inputCache.get(f.id)??f.example;input.oninput=()=>inputCache.set(f.id,input.value);
 box.append(el('h2',f.title+' Agent'),el('small','后端执行已发布版本 v'+state.version));field(box,'业务输入',input);const result=el('div',undefined,'result');
 const run=btn('运行 Agent',async()=>{loading=true;run.disabled=true;feature.disabled=true;say('正在执行发布流程…');try{
 const data=await submitRun(f.id,state.version,input.value);evidence(result,data);say(states[data.status]||data.status)
 }finally{loading=false;feature.disabled=false;run.disabled=false}},'primary');box.append(run,result);content.append(box);
}
function render(){
 say('');content.replaceChildren();document.querySelectorAll('nav button').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.onclick=()=>{if(b.dataset.view==='history'&&!loading){view='history';guard(async()=>{state=await api(f.id);replay=null;render()})}else go(b.dataset.view)}});
 if(view==='design')design();else if(view==='pc')pc();else if(view==='phone'){const frame=el('iframe');frame.title='手机工作台';frame.src='?mobile=1&app='+f.id;content.append(frame)}
 else if(replay){content.append(btn('返回运行列表',async()=>{replay=null;state=await api(f.id);render()}),el('h2','执行回放 · v'+replay.version+' · '+(states[replay.status]||replay.status)),el('small',replay.id));
 const grid=el('div',undefined,'grid');content.append(grid);draw(grid,replay.graph,true);
 const log=el('section',undefined,'box');log.append(el('h3','执行顺序'));for(const e of replay.trace){log.append(btn(e.seq+' '+names[e.node],()=>{selected=e.node;render()}))}log.append(el('pre',j(replay.ledger)));content.append(log)
 }else {content.append(el('h2','运行记录'));if(!state.history.length)content.append(el('p','提交一次业务输入后可在此回放。'));for(const h of state.history)content.append(btn('v'+h.ver+' · '+(states[h.status]||h.status)+' · '+h.id,async()=>{replay=await api(f.id+'/runs/'+h.id);selected='start';render()},'history'))}
}
await guard(async()=>{info=await api('session',{});for(const t of info.features){const o=el('option',t.title);o.value=t.id;feature.append(o)}
 f=info.features.find(v=>v.id===params.get('app'))||info.features[0];feature.value=f.id;feature.onchange=()=>{if(dirty&&!confirm('放弃未保存草稿？')){feature.value=f.id;return}f=info.features.find(v=>v.id===feature.value);selected='start';guard(fresh)};await fresh()});
