/* ==================== JARVIS ANDROID · LAYER 4 (v1.2) ====================
   Системные уведомления: напоминания («напомни через 5 минут…»,
   «напомни в 15:30…», «напомни завтра в 7 утра…»), таймеры
   («таймер на 10 минут»), список/отмена, тест связи.
   Натив: NotificationChannel + AlarmManager + BroadcastReceiver —
   срабатывают даже если приложение выгружено из памяти.             */
(function(){
'use strict';
if(!window.Android) return;

/* ---------- 0. Разбор длительностей ---------- */
var WNUM={'один':1,'одну':1,'два':2,'две':2,'три':3,'четыре':4,'пять':5,'шесть':6,
  'семь':7,'восемь':8,'девять':9,'десять':10,'одиннадцать':11,'двенадцать':12,
  'пятнадцать':15,'двадцать':20,'тридцать':30,'сорок пять':45};
var WNUM_RE='\\d+|полчаса|полминуты|один|одну|две|два|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|пятнадцать|двадцать|тридцать|сорок\\s+пять';

function pad(n){ return (n<10?'0':'')+n; }
function fmtDur(sec){
  sec=Math.max(1,Math.round(sec));
  if(sec<60) return sec+' сек';
  var h=Math.floor(sec/3600), m=Math.floor((sec%3600+30)/60);
  if(m>=60){ h++; m=0; }
  if(h&&m) return h+' ч '+m+' мин';
  if(h) return h+' ч';
  return m+' мин';
}
/* «5 минут позвонить маме» → {sec:300, text:'позвонить маме', label:'через 5 мин'} */
function parseDur(s){
  s=String(s||'').trim();
  /* NB: \w в JS не матчит кириллицу — окончания ловим через [а-яё] */
  var m=s.match(new RegExp('^('+WNUM_RE+')\\s*(секунд[а-яё]*|сек|минут[а-яё]*|мин|час[а-яё]*|ч)?\\s*[,–—-]?\\s*(.*)$','i'));
  if(!m) return null;
  var w=m[1].toLowerCase().replace(/\s+/g,' ').trim(), u=(m[2]||'').toLowerCase(), sec;
  if(w==='полчаса') sec=1800;
  else if(w==='полминуты') sec=30;
  else{
    var n=/^\d+$/.test(w)?parseInt(w,10):(WNUM[w]||0);
    if(!n) return null;
    if(u.indexOf('сек')===0) sec=n;
    else if(u.indexOf('час')===0||u==='ч') sec=n*3600;
    else sec=n*60; /* минуты — по умолчанию: «напомни через 5» = 5 мин */
  }
  var txt=(m[3]||'').replace(/^(?:о\s+том[,\s]+что|чтобы)\s+/i,'').replace(/[.?!…]+$/,'').trim();
  return {sec:sec, text:txt, label:'через '+fmtDur(sec)};
}

/* ---------- 1. Голосовые команды (BACKEND_PATTERNS проверяются первыми) ---------- */
try{
  BACKEND_PATTERNS.push(
    /* напомни через 5 минут позвонить маме */
    [/^напомн(?:и|ь)\s+(?:мне\s+)?через\s+(.+)$/i, 'remind_in', function(m){
      var p=parseDur(m[1]);
      return p?[p.sec,p.text,p.label]:[0,'',''];
    }],
    /* напомни (завтра) в 15:30 / в 7 утра / в 7 вечера — забрать посылку */
    [/^напомн(?:и|ь)\s+(?:мне\s+)?(завтра\s+)?в\s+(\d{1,2})(?:\s*[:.]\s*(\d{1,2}))?\s*(утра|вечера|дня|ночи)?\s*[,–—-]?\s*(.+)$/i, 'remind_in', function(m){
      var tomorrow=!!m[1];
      var h=parseInt(m[2],10), mi=m[3]?parseInt(m[3],10):0;
      var pm=(m[4]==='вечера'||m[4]==='дня');
      if(pm&&h<12)h+=12;
      if((m[4]==='ночи'||m[4]==='утра')&&h===12)h=0;
      if(h>23||mi>59) return [0,'',''];
      var now=new Date(), t=new Date(now.getFullYear(),now.getMonth(),now.getDate(),h,mi,0);
      if(tomorrow||t.getTime()<=now.getTime()+30000) t=new Date(t.getTime()+86400000);
      return [Math.round((t.getTime()-now.getTime())/1000), String(m[5]).trim(),
              (tomorrow?'завтра в ':'в ')+pad(h)+':'+pad(mi)];
    }],
    /* таймер на 10 минут / таймер полчаса / таймер 90 секунд */
    [/^(?:поставь\s+)?(?:таймер|timer)(?:\s+на)?\s+(.+)$/i, 'timer_start', function(m){
      var p=parseDur(m[1]);
      return p?[p.sec]:[0];
    }],
    [/^(?:тест|проверка|проверь)\s+уведомлен\w*/i, 'notify_test', function(){ return []; }],
    [/^(?:покажи|список|какие)\s+напоминания|^мои\s+напоминания/i, 'notif_list', function(){ return []; }],
    [/^отмени(?:ть)?\s+(?:все\s+)?(?:напоминания|напоминалк\w+|таймер\w*)/i, 'notif_cancel_all', function(){ return []; }],
    [/^отмени\s+напоминание\s+(?:№|#)?\s*(\d+)/i, 'notif_cancel', function(m){ return [parseInt(m[1],10)]; }]
  );
}catch(e){}

/* ---------- 2. Сработавшее напоминание (натив → лог; озвучку делает
   фоновый NotifSpeakerService с v1.7 — она работает даже когда
   приложение свёрнуто или убито, поэтому тут только лог) ---------- */
window.__jarvisNotifFired=function(id,title,text){
  try{
    var msg=String(text||'')||'Напоминание, сэр';
    addLog('jarvis','⏰ '+msg);
  }catch(e){}
};

/* ---------- 3. Чип «🔔» в шапке — тест уведомлений в один тап ---------- */
try{
  var host=document.querySelector('.hdr-right');
  if(host && !document.getElementById('notifChip')){
    var b=document.createElement('button');
    b.className='chip'; b.id='notifChip'; b.textContent='🔔';
    b.title='Тест уведомлений';
    b.style.cursor='pointer'; b.style.fontSize='12px'; b.style.padding='4px 10px';
    b.onclick=function(){
      try{
        var r=JSON.parse(Android.exec(JSON.stringify({cmd:'notify_test',args:[]})));
        addLog('sys',(r&&r.ok)?('🔔 '+(r.stdout||'готово')):('❌ '+((r&&r.error)||(r&&r.stderr)||'ошибка')));
      }catch(e){ addLog('sys','❌ '+e); }
    };
    host.insertBefore(b,host.firstChild);
  }
}catch(e){}

console.log('%c[JARVIS ANDROID] Layer 4: уведомления и напоминания','color:#42ffb0;font-weight:bold');
})();
