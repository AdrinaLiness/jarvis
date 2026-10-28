/* ==================== JARVIS ANDROID · LAYER 2 ====================
   Нативный голос (TTS), ИИ-мозг (chat/completions + метки действий),
   телефонные команды (фонарик/заряд/настройки), панель ИИ-настроек.   */
(function(){
'use strict';
if(!window.Android) return;

var AI_PERSONA_DEFAULT='Ты — J.A.R.V.I.S. (Just A Rather Very Intelligent System), персональный ИИ-ассистент. Общайся на русском, кратко, по делу, с лёгким юмором и обращайся к пользователю как к «сэр». Ты управляешь его телефоном. Чтобы выполнить действие, в конце ответа вставь отдельной строкой метку: [[OPEN:https://...]] — открыть ссылку, [[RUN:название]] — запустить приложение на телефоне по названию (например: ютуб, телеграм, whatsapp, карты) или ярлыку (telegram, chrome, spotify), [[SEARCH:запрос]] — искать в интернете. Если действие не требуется — метку не ставь. Никогда не объясняй формат меток.';

/* ---------- 1. Нативный TTS вместо speechSynthesis ---------- */
window.__ttsDone=function(token){
  if(token===speakToken){
    try{ stopHum(); }catch(e){}
    if(mode==='speaking') setMode('idle');
  }
};
var __origSpeak=speak;
speak=function(text,opts){
  opts=opts||{};
  if(!soundOn) return;
  try{
    var useEn=(voiceMode==='authentic');
    var phr=chunkPhrases(text);
    if(!phr.length) return;
    var preType=opts.preType||null;
    if(opts.preset&&SPEAK_PRESETS[opts.preset]&&SPEAK_PRESETS[opts.preset].pre) preType=SPEAK_PRESETS[opts.preset].pre;
    if(preType&&!opts.skipPre){ var pp=getPreFiller(preType,useEn); if(pp) phr.unshift(pp); }
    if(opts.preset&&SPEAK_PRESETS[opts.preset]&&SPEAK_PRESETS[opts.preset].scan) jarvisScan();
    else jarvisBlip();
    var preset=opts.preset?SPEAK_PRESETS[opts.preset]:SPEAK_PRESETS.default;
    var rate=(typeof opts.rate==='number')?opts.rate:(preset.rate*voiceRate);
    var pitch=(typeof opts.pitch==='number')?opts.pitch:(preset.pitch*voicePitch);
    var token=++speakToken;
    setMode('speaking');
    Android.speak(JSON.stringify({
      token:String(token),
      text:phr.join(' '),
      lang:useEn?'en-GB':'ru-RU',
      rate:Math.max(0.5,Math.min(2,rate)),
      pitch:Math.max(0.5,Math.min(2,pitch))
    }));
    /* страховка: если TTS-движок молчит — не зависаем в «ОТВЕТ» */
    setTimeout(function(){
      if(token===speakToken&&mode==='speaking'){ try{ stopHum(); }catch(e){} setMode('idle'); }
    },60000);
  }catch(e){
    /* запасной путь — браузерный синтез */
    __origSpeak(text,opts);
  }
};

/* ---------- 2. Телефонные команды ---------- */
try{
  BACKEND_PATTERNS.push(
    [/^(?:включи|зажги) фонарик$/i,'torch_on',function(){return[];}],
    [/^(?:выключи|потуши) фонарик$/i,'torch_off',function(){return[];}],
    [/^(?:батарея|аккумулятор)\s*[?]?$|^(?:какой\s+)?заряд(?:\s+телефона)?\s*[?]?$|^сколько заряда/i,'battery',function(){return[];}],
    [/^открой настройки(?:\s+телефона)?$/i,'open_settings',function(){return[];}],
    [/^(?:включи|открой)\s+(?:вай-?фай|ви-?фи|wi-?fi)\s*$/i,'open_wifi',function(){return[];}],
    [/^(?:включи|открой)\s+(?:блютуз|bluetooth)\s*$/i,'open_bluetooth',function(){return[];}]
  );
}catch(e){}

/* ---------- 3. ИИ-МОЗГ: fallback → нейросеть ---------- */
var AI={hist:[]};
function aiCfg(){
  try{ var s=JSON.parse(Android.getSettings()); return s.ai||{}; }catch(e){ return {}; }
}
function runAIMarkers(text){
  var re=/\[\[(OPEN|RUN|SEARCH)\s*:\s*([^\]]+)\]\]/gi,m;
  while((m=re.exec(String(text)))!==null){
    var kind=m[1].toUpperCase(), val=m[2].trim();
    try{
      if(kind==='RUN'){
        var r=JSON.parse(Android.exec(JSON.stringify({cmd:'launch_app',args:[val]})));
        addLog('sys', r&&r.ok?('✅ Запуск: '+val):(r&&r.error?('❌ '+r.error):('❌ Не найдено: '+val)));
      } else if(kind==='OPEN'){
        Android.exec(JSON.stringify({cmd:'open_url',args:[val]}));
        addLog('sys','🔗 Открываю: '+val);
      } else {
        Android.exec(JSON.stringify({cmd:'open_url',args:['https://www.google.com/search?q='+encodeURIComponent(val)]}));
        addLog('sys','🔎 Ищу: '+val);
      }
    }catch(e){}
  }
}
var __origSmartFallback=smartFallback;
smartFallback=function(text){
  var cfg=null;
  try{ cfg=aiCfg(); }catch(e){}
  if(cfg&&cfg.enabled&&cfg.api_key&&cfg.base_url){
    var msgs=[{role:'system',content:cfg.persona||AI_PERSONA_DEFAULT}];
    for(var i=Math.max(0,AI.hist.length-6);i<AI.hist.length;i++) msgs.push(AI.hist[i]);
    msgs.push({role:'user',content:String(text)});
    addLog('sys','🧠 ИИ-МОЗГ: запрос отправлен...');
    try{
      var raw=Android.chat(JSON.stringify({messages:msgs,base_url:cfg.base_url,model:cfg.model,api_key:cfg.api_key}));
      var j=JSON.parse(raw);
      if(j&&j.ok&&j.content){
        AI.hist.push({role:'user',content:String(text)});
        AI.hist.push({role:'assistant',content:String(j.content)});
        if(AI.hist.length>12) AI.hist=AI.hist.slice(-12);
        runAIMarkers(j.content);
        var cleanTxt=String(j.content).replace(/\[\[(OPEN|RUN|SEARCH)[^\]]*\]\]/gi,'').replace(/\n{3,}/g,'\n\n').trim();
        return {ru:cleanTxt||'Готово, сэр.',en:cleanTxt||'Done, sir.'};
      }
      return {ru:'Сэр, ИИ-модуль недоступен: '+((j&&j.error)||'нет связи')+'. Проверьте настройки ИИ (кнопка в шапке).',en:'AI module offline.'};
    }catch(e){
      return {ru:'Сэр, сбой ИИ-модуля: '+e,en:'AI error.'};
    }
  }
  return __origSmartFallback.apply(null,arguments);
};

/* ---------- 4. Панель настроек ИИ ---------- */
function buildAIPanel(){
  if(document.getElementById('aiPanel')) return;
  var st=document.createElement('style');
  st.textContent=
  '#aiPanel{position:fixed;inset:0;z-index:9999;display:none;align-items:center;justify-content:center;background:rgba(1,7,11,.82)}'+
  '#aiPanel.on{display:flex}'+
  '.ai-box{width:min(92vw,540px);max-height:86vh;overflow:auto;background:#04141f;border:1px solid rgba(56,232,255,.4);box-shadow:0 0 40px rgba(56,232,255,.25);padding:18px;color:#cfeeff}'+
  '.ai-box h3{margin:0 0 4px;color:#38e8ff;font-family:Orbitron,sans-serif;letter-spacing:.2em;font-size:14px}'+
  '.ai-box .ai-sub{opacity:.6;font-size:10px;letter-spacing:.3em;margin-bottom:14px}'+
  '.ai-row{margin-bottom:10px}'+
  '.ai-row label{display:block;font-size:10px;letter-spacing:.15em;opacity:.75;margin-bottom:4px}'+
  '.ai-row input,.ai-row textarea{width:100%;box-sizing:border-box;background:rgba(2,14,20,.8);border:1px solid rgba(56,232,255,.3);color:#d9f6ff;padding:8px 10px;font-family:inherit;font-size:12px;outline:none}'+
  '.ai-row textarea{min-height:90px;resize:vertical}'+
  '.ai-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}'+
  '.ai-btn{background:transparent;border:1px solid rgba(56,232,255,.5);color:#8fe8ff;padding:8px 14px;font-family:inherit;font-size:11px;letter-spacing:.12em;cursor:pointer}'+
  '.ai-btn:active{background:rgba(56,232,255,.15)}'+
  '.ai-note{margin-top:10px;font-size:11px;opacity:.85;white-space:pre-wrap;min-height:14px}';
  document.head.appendChild(st);
  var p=document.createElement('div');
  p.id='aiPanel'; p.className='ai-panel';
  p.innerHTML=
  '<div class="ai-box"><h3>ИИ-МОЗГ JARVIS</h3><div class="ai-sub">МОДЕЛЬ · КЛЮЧ · МЕТКИ ДЕЙСТВИЙ</div>'+
  '<div class="ai-row"><label>СОСТОЯНИЕ</label><button class="ai-btn" id="aiOn">ВКЛ / ВЫКЛ</button> '+
  '<span id="aiOnState" style="font-size:11px;opacity:.8"></span></div>'+
  '<div class="ai-row"><label>БАЗОВЫЙ URL API</label><input id="aiBase" placeholder="https://ai.starimg.ru/v1"></div>'+
  '<div class="ai-row"><label>МОДЕЛЬ</label><input id="aiModel" placeholder="deepseek4pro"></div>'+
  '<div class="ai-row"><label>API-КЛЮЧ</label><input id="aiKey" type="password" placeholder="вставьте ключ"></div>'+
  '<div class="ai-row"><label>ЛИЧНОСТЬ (СИСТЕМНЫЙ ПРОМПТ)</label><textarea id="aiPersona"></textarea></div>'+
  '<div class="ai-actions"><button class="ai-btn" id="aiSave">СОХРАНИТЬ</button>'+
  '<button class="ai-btn" id="aiTest">ПРОВЕРИТЬ СВЯЗЬ</button>'+
  '<button class="ai-btn" id="aiClose">ЗАКРЫТЬ</button></div>'+
  '<div class="ai-note" id="aiNote">—</div></div>';
  document.body.appendChild(p);
  p.addEventListener('click',function(e){ if(e.target===p) p.classList.remove('on'); });
  function note(t){ var n=document.getElementById('aiNote'); if(n) n.textContent=t; }
  document.getElementById('aiClose').onclick=function(){ p.classList.remove('on'); };
  document.getElementById('aiOn').onclick=function(){
    try{
      var c=aiCfg(); c.enabled=!c.enabled;
      var k=document.getElementById('aiKey').value.trim(); if(k) c.api_key=k;
      var s=JSON.parse(Android.getSettings()); s.ai=c;
      Android.saveSettings(JSON.stringify(s));
      aiFill(c); aiChipSync();
      note(c.enabled?'мозг включён':'мозг выключен');
    }catch(e){ note('ошибка: '+e); }
  };
  document.getElementById('aiSave').onclick=function(){
    try{
      var s=JSON.parse(Android.getSettings());
      s.ai=s.ai||{};
      s.ai.base_url=document.getElementById('aiBase').value.trim()||'https://ai.starimg.ru/v1';
      s.ai.model=document.getElementById('aiModel').value.trim()||'deepseek4pro';
      var k=document.getElementById('aiKey').value.trim(); if(k) s.ai.api_key=k;
      s.ai.persona=document.getElementById('aiPersona').value;
      Android.saveSettings(JSON.stringify(s));
      aiChipSync(); note('сохранено ✓');
    }catch(e){ note('ошибка: '+e); }
  };
  document.getElementById('aiTest').onclick=function(){
    note('проверяю связь…');
    try{
      var c=aiCfg();
      var raw=Android.chat(JSON.stringify({messages:[{role:'user',content:'Ответь одним словом: работаешь?'}],base_url:c.base_url,model:c.model,api_key:c.api_key}));
      var j=JSON.parse(raw);
      note(j&&j.ok?('связь ✓ — '+String(j.content).slice(0,120)):('ошибка: '+((j&&j.error)||'?')));
    }catch(e){ note('ошибка: '+e); }
  };
}
function aiFill(c){
  try{
    document.getElementById('aiBase').value=c.base_url||'https://ai.starimg.ru/v1';
    document.getElementById('aiModel').value=c.model||'deepseek4pro';
    var k=document.getElementById('aiKey');
    k.value='';
    k.placeholder=c.api_key?('сохранён: '+String(c.api_key).slice(0,10)+'…'):'вставьте ключ';
    document.getElementById('aiPersona').value=c.persona||AI_PERSONA_DEFAULT;
    document.getElementById('aiOnState').textContent=c.enabled?'ВКЛ':'ВЫКЛ';
  }catch(e){}
}
function openAIPanel(){
  buildAIPanel();
  aiFill(aiCfg());
  document.getElementById('aiPanel').classList.add('on');
}
function aiChipSync(){
  var b=document.getElementById('aiChip'); if(!b) return;
  var c=null; try{ c=aiCfg(); }catch(e){}
  var on=!!(c&&c.enabled&&c.api_key);
  b.textContent=on?'ИИ МОЗГ · ON':'ИИ: ВЫКЛ';
  b.style.color=on?'#42ffb0':'';
  b.style.borderColor=on?'#42ffb0':'';
  b.style.textShadow=on?'0 0 12px rgba(66,255,176,.8)':'';
}
try{
  var host=document.querySelector('.hdr-right');
  if(host){
    var b=document.createElement('button');
    b.className='chip'; b.id='aiChip'; b.textContent='ИИ: ВЫКЛ';
    b.style.cursor='pointer'; b.style.fontSize='10px'; b.style.padding='5px 10px'; b.style.letterSpacing='.15em';
    b.onclick=openAIPanel;
    host.insertBefore(b,host.firstChild);
  }
}catch(e){}
try{ aiChipSync(); }catch(e){}

/* ---------- 5. Микрофон: сразу в нативный STT (без getUserMedia-гейта) ---------- */
try{
  var micBtn=document.getElementById('mic');
  if(micBtn){
    var micClone=micBtn.cloneNode(true);
    micBtn.parentNode.replaceChild(micClone,micBtn);
    micClone.addEventListener('click',function(){
      try{
        if(!recog) recog=initRecog();
        if(listening){
          listening=false;
          try{ recog.stop(); }catch(e){}
          micClone.classList.remove('active');
          if(mode==='listening') setMode('idle');
          blip(320);
          return;
        }
        blip(660);
        recog.start(); /* NativeSR.start → onstart → Android.startSTTLang */
      }catch(e){ window.__nativeSRError('stt-failed'); }
    });
  }
}catch(e){}

/* ---------- 6. Мелочи ---------- */
window.__jarvisAISync=aiChipSync; /* внешняя синхронизация чипа ИИ */
/* аудит-панель на телефоне обновляется из нативного журнала */
try{ if(typeof refreshAudit==='function') refreshAudit(); }catch(e){}
console.log('%c[JARVIS ANDROID] Layer 2: голос, мозг, команды телефона','color:#42ffb0;font-weight:bold');
})();
