/* ==================== JARVIS ANDROID · LAYER 5 (v1.5) ====================
   Телефон под командой: отправка SMS («отправь смс маме куплю хлеб»),
   чаты мессенджеров («напиши маме в ватсап привет», «напиши в телеграм
   @user как дела?»), звонки («позвони маме», «набери номер +7…»),
   статус/вкл-выкл чтения СМС вслух, последние сообщения.
   Натив: SmsManager + SEND_SMS, deep links wa.me / tg://resolve /
   viber://chat / smsto:, PhoneLookup (имена), SmsReceiver + TTS в фоне.  */
(function(){
'use strict';
if(!window.Android) return;

/* ---------- 0. Справочник мессенджеров ---------- */
var APPS={
  'whatsapp':['ватсап','вотсап','вапсап','ватсапп','whatsapp','вотсап'],
  'telegram':['телеграм','телега','телегу','телеге','тг','telegram'],
  'viber':['вайбер','viber','вайбр'],
  'vk':['вк','вконтакте','vk','вконтакт'],
  'sms':['смс','sms','сообщения','сообщении','сообщеньям']
};
function appFrom(s){
  s=String(s||'').toLowerCase().replace(/ё/g,'е').trim();
  for(var k in APPS){ if(APPS[k].indexOf(s)>=0) return k; }
  return null;
}
function exec(cmd,args){
  try{
    return JSON.parse(Android.exec(JSON.stringify({cmd:cmd,args:args||[]})));
  }catch(e){ return {ok:false,error:String(e)}; }
}
function logR(r,fallback){
  if(r&&r.ok) addLog('sys','✅ '+(r.stdout||fallback||'готово'));
  else addLog('sys','❌ '+((r&&(r.error||r.stderr))||fallback||'ошибка'));
}

/* ---------- 1. Голосовые команды (BACKEND_PATTERNS проверяются первыми) ---------- */
try{
  BACKEND_PATTERNS.push(
    /* «напиши маме в ватсап привет» — кому → в мессенджер → текст */
    [/^(?:напиши|отправь|скинь)\s+(?:сообщение\s+)?(.+?)\s+в\s+(ватсап|вотсап|whatsapp|вайбер|viber|телеграм|телегу|телеге|телега|тг|telegram|вк|вконтакте|vk|смс|sms|сообщения)\s+(.+)$/i,
     'open_chat', function(m){
      var app=appFrom(m[2]); if(!app) return [];
      return [app,String(m[1]).trim(),String(m[3]).trim()];
    }],
    /* «напиши в ватсап маме привет» / «напиши в телеграм @user» — в мессенджер → кому → текст */
    [/^(?:напиши|отправь|скинь)\s+в\s+(ватсап|вотсап|whatsapp|вайбер|viber|телеграм|телегу|телеге|телега|тг|telegram|вк|вконтакте|vk|смс|sms|сообщения)\s+(.+)$/i,
     'open_chat', function(m){
      var app=appFrom(m[1]); if(!app) return [];
      var rest=String(m[2]).trim();
      var sp=rest.indexOf(' ');
      if(sp<0) return [app,rest,''];
      return [app,rest.slice(0,sp).trim(),rest.slice(sp+1).trim()];
    }],
    /* «отправь смс +79991234567 привет, сэр» / «отправь смс на маму куплю хлеб» */
    [/^(?:отправ(?:ь|ить)|напиши|скинь)\s+(?:смс|sms|сообщение)\s+(?:на\s+|для\s+)?(.+)$/i,
     'send_sms', function(m){
      var rest=String(m[1]).trim().replace(/[.?!…]+$/,'');
      /* получатель-номер: длинный числовой блок в начале */
      var mm=rest.match(/^([+0-9()\- ]{10,})(?:\s+(.*))?$/);
      if(mm) return [mm[1].trim(),(mm[2]||'').trim()];
      var sp=rest.indexOf(' ');
      if(sp<0) return [rest,''];
      return [rest.slice(0,sp).trim(), rest.slice(sp+1).trim()];
    }],
    /* «позвони маме» / «позвони +79991234567» — прямой звонок */
    [/^позвон(?:и|ить)\s+(?:на\s+)?(.+)$/i, 'call', function(m){
      return [String(m[1]).trim().replace(/[.?!…]+$/,'')];
    }],
    /* «набери номер +79991234567» / «набери маму» — открыть дозвонщик */
    [/^(?:набер(?:и|ите)|набрать)\s+(?:номер\s+)?(.+)$/i, 'dial', function(m){
      return [String(m[1]).trim().replace(/[.?!…]+$/,'')];
    }],
    /* «включи/выключи чтение смс» / «читай смс» */
    [/^(?:выключи|отключи)\s+(?:чтение\s+)?смс/i, 'sms_toggle', function(){ return ['выкл']; }],
    [/^(?:включи|разреши)\s+(?:чтение\s+)?смс/i, 'sms_toggle', function(){ return ['вкл']; }],
    [/^(?:читай|озвучивай|произноси)\s+смс/i, 'sms_toggle', function(){ return ['вкл']; }],
    /* «как там смс» / «статус смс» / «настройки смс» */
    [/^(?:как\s+там|статус|состояние|настройки)\s+смс/i, 'sms_status', function(){ return []; }],
    /* «мои смс» / «последние смс» / «покажи последние сообщения» */
    [/^(?:мои\s+смс|последние\s+(?:смс|сообщения)|покажи\s+последние\s+(?:смс|сообщения)|прочитай\s+последние\s+(?:смс|сообщения))$/i,
     'sms_list', function(){ return []; }]
  );
}catch(e){}

/* ---------- 2. Входящее SMS (натив → лог; озвучка делает сервис) ---------- */
window.__jarvisSms=function(from,body){
  try{
    addLog('sys','📱 СМС от '+String(from||'?')+': '+String(body||''));
  }catch(e){}
};
window.__jarvisSmsLog=function(text){
  try{ addLog('sys','✉️ '+String(text||'')); }catch(e){}
};

/* ---------- 3. Чип «✉️» в шапке — вкл/выкл чтения СМС в один тап ---------- */
try{
  var host=document.querySelector('.hdr-right');
  if(host && !document.getElementById('smsChip')){
    var b=document.createElement('button');
    b.className='chip'; b.id='smsChip'; b.textContent='✉️';
    b.style.cursor='pointer'; b.style.fontSize='12px'; b.style.padding='4px 10px';
    var refresh=function(){
      try{
        var r=exec('sms_status',[]);
        var on=r&&r.speak;
        b.style.opacity=on?'1':'0.4';
        b.title='Чтение СМС вслух: '+(on?'ВКЛ':'выкл')
              +(r&&r.receive?'':' (нет разрешения на СМС)');
      }catch(e){}
    };
    b.onclick=function(){
      var r=exec('sms_toggle',[]);
      if(r&&r.ok){ addLog('jarvis','✉️ '+(r.stdout||'готово')); }
      else addLog('sys','❌ '+((r&&(r.error||r.stderr))||'ошибка'));
      refresh();
    };
    refresh();
    host.insertBefore(b,host.firstChild);
  }
}catch(e){}

console.log('%c[JARVIS ANDROID] Layer 5: смс, мессенджеры, звонки','color:#42ffb0;font-weight:bold');
})();
