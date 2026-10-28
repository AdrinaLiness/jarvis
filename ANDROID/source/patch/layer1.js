/* ==================== JARVIS ANDROID · LAYER 1 (shims) ====================
   Работает только внутри APK (window.Android).
   1) WebSocket → нативный мост (синхронный exec без реального сокета)
   2) SpeechRecognition → нативный STT (диалог Google)
   3) fetch-шим для /api/settings*, /audit                                   */
(function(){
'use strict';
if(!window.Android) return;

/* --- 1. WebSocket → мост --- */
function FakeWebSocket(url){
  this.url=url||''; this.readyState=0; this.binaryType='blob';
  this.onopen=null; this.onclose=null; this.onerror=null; this.onmessage=null;
  var self=this;
  setTimeout(function(){ self.readyState=1; try{ self.onopen&&self.onopen(); }catch(e){} },30);
}
FakeWebSocket.prototype.send=function(payload){
  var self=this;
  setTimeout(function(){
    var out;
    try{
      var msg=JSON.parse(payload);
      out=JSON.parse(Android.exec(payload));
      if(msg.cmd==='volume_set'&&msg.args&&msg.args.length){ try{ window._lastVolume=msg.args[0]; }catch(e){} }
    }catch(e){ out={ok:false,error:String(e)}; }
    setTimeout(function(){ try{ self.onmessage&&self.onmessage({data:JSON.stringify(out)}); }catch(e){} },0);
  },0);
};
FakeWebSocket.prototype.close=function(){ this.readyState=3; try{ this.onclose&&this.onclose(); }catch(e){} };
window.WebSocket=FakeWebSocket;

/* --- 2. SpeechRecognition → нативный STT --- */
function NativeSR(){
  this.lang='ru-RU'; this.continuous=false; this.interimResults=false; this.maxAlternatives=1;
  this.onstart=null; this.onend=null; this.onerror=null; this.onresult=null;
  NativeSR._inst=this;
}
NativeSR._inst=null;
NativeSR.prototype.start=function(){
  var self=this;
  setTimeout(function(){ try{ self.onstart&&self.onstart(); }catch(e){} },0);
  try{ Android.startSTTLang(this.lang||'ru-RU'); }
  catch(e){ try{ Android.startSTT(); }catch(e2){ window.__nativeSRError('stt-unavailable'); } }
};
NativeSR.prototype.stop=function(){ var self=this; setTimeout(function(){ try{ self.onend&&self.onend(); }catch(e){} },0); };
NativeSR.prototype.abort=function(){ this.stop(); };
window.SpeechRecognition=NativeSR;
window.webkitSpeechRecognition=NativeSR;
window.__nativeSRResult=function(text,isFinal){
  var r=NativeSR._inst; if(!r) return;
  window.listening=false; /* один тап — одна фраза */
  try{
    if(r.onresult) r.onresult({ resultIndex:0, length:1,
      results:{ length:1, 0:{ length:1, 0:{transcript:String(text||'')}, isFinal:!!isFinal } } });
  }catch(e){}
  try{ r.onend&&r.onend(); }catch(e){}
};
window.__nativeSRError=function(msg){
  var r=NativeSR._inst; if(!r) return;
  window.listening=false;
  try{ r.onerror&&r.onerror({error:String(msg||'error')}); }catch(e){}
  try{ r.onend&&r.onend(); }catch(e){}
};

/* --- 3. fetch-шим --- */
var _fetch=window.fetch?window.fetch.bind(window):null;
function jresp(data){
  return { ok:true, status:200, statusText:'OK',
    json:function(){ return Promise.resolve(data); },
    text:function(){ return Promise.resolve(JSON.stringify(data)); } };
}
window.fetch=function(url,opts){
  var u=String(url||'');
  try{
    if(u.indexOf('/api/settings/reset')===0){
      return Promise.resolve(jresp(JSON.parse(Android.resetSettings())));
    }
    if(u.indexOf('/api/settings')===0){
      if(opts&&String(opts.method||'').toUpperCase()==='POST'){
        Android.saveSettings(opts.body||'{}');
        return Promise.resolve(jresp({ok:true}));
      }
      return Promise.resolve(jresp(JSON.parse(Android.getSettings())));
    }
    if(u==='/audit'||u.indexOf('/audit?')===0){
      return Promise.resolve(jresp(JSON.parse(Android.audit())));
    }
  }catch(e){ return Promise.resolve(jresp({ok:false,error:String(e)})); }
  if(_fetch) return _fetch(url,opts);
  return Promise.resolve(jresp({}));
};
console.log('%c[JARVIS ANDROID] Layer 1: мост готов','color:#42ffb0;font-weight:bold');
})();
