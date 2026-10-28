/* ==================== JARVIS ANDROID · LAYER 3 ====================
   Комфорт в портрете и ландшафте: компактная шапка, чипы в одну
   строку со скроллом, крупные поля ввода в портрете, 3 колонки
   в ландшафте, реактор уменьшается на низких экранах.            */
(function(){
'use strict';
if(!window.Android) return;

var st=document.createElement('style');
st.id='jarvisOrient';
st.textContent=
/* ---------- ГЛОБАЛЬНО: input во flex не сжимается без min-width:0 ---------- */
'#cmd{min-width:0}'+
/* ---------- ГЛОБАЛЬНО: сжатие грид-колонок (min-content канвасов
   и шапки растягивал layout до ~844px в портрете) ---------- */
'#app>header,#app>main,#app>footer{min-width:0}'+
'main>.panel,main>section.center{min-width:0}'+
'section.center,.reactor-wrap,.right{overflow:hidden}'+
'.hdr-right{flex-wrap:wrap;min-width:0;row-gap:4px}'+
'.msg{overflow-wrap:anywhere;min-width:0}'+
'.msg .who{white-space:normal}'+
/* ---------- ПОРТРЕТ ---------- */
'@media (orientation:portrait){'+
'header{padding:8px 12px}'+
'.brand small{display:none}'+                        /* подзаголовок — только в ландшафте */
'.clock{font-size:14px}'+
'#cmd{font-size:16px;padding:12px 14px}'+          /* 16px — без автозума клавиатурой */
'#send{padding:12px 18px}'+
'.chips{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none;margin-bottom:8px}'+
'.chips::-webkit-scrollbar{display:none}'+
'.chip{flex:0 0 auto}'+
'.right{max-height:34vh}'+                            /* лог выше, чем 190px */
'.reactor-wrap{min-height:0;height:28vh;flex:0 0 auto}'+
'}'+
/* ---------- ЛАНДШАФТ ТЕЛЕФОНА ---------- */
'@media (orientation:landscape) and (max-height:640px){'+
'header{padding:3px 12px}'+
'.clock{font-size:12px}'+
'main{grid-template-columns:210px 1fr 240px!important;gap:8px!important;padding:8px!important}'+
'.left{display:block!important}'+                     /* вернуть метрики */
'.right{max-height:none!important}'+
'.center{display:flex;flex-direction:column;min-height:0}'+
'.reactor-wrap{min-height:0!important;height:38%;flex:0 0 auto}'+
'.msg{font-size:11px}'+
'.chips{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none;margin-bottom:5px}'+
'.chips::-webkit-scrollbar{display:none}'+
'.chip{flex:0 0 auto;padding:3px 8px;font-size:9px}'+
'footer{padding:5px 12px 7px}'+
'#cmd{padding:7px 10px;font-size:12.5px}'+
'#send{padding:7px 12px}'+
'.audit-list{max-height:110px}'+
'}'+
/* ---------- ОЧЕНЬ НИЗКИЙ ЛАНДШАФТ: реактор уступает место ---------- */
'@media (orientation:landscape) and (max-height:380px){'+
'.reactor-wrap{display:none}'+
'main{grid-template-columns:180px 1fr 230px!important}'+
'}';
document.body.appendChild(st); /* базовые <style> страницы — в body: цепляемся после них */
console.log('%c[JARVIS ANDROID] Layer 3: ориентации настроены','color:#42ffb0;font-weight:bold');
})();
