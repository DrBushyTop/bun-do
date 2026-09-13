// Throwaway browser study, not shared storage. Refresh restores the fixtures.
// Refine the selected design, rather than introducing new layout variants.
for(const [id,paths] of Object.entries({
  order:'<path d="M8 4v16m-4-4 4 4 4-4M16 20V4m-4 4 4-4 4 4"/>',
  grip:'<path d="M8 5h.01M16 5h.01M8 12h.01M16 12h.01M8 19h.01M16 19h.01" stroke-width="4"/>',
  up:'<path d="m6 15 6-6 6 6"/>', down:'<path d="m6 9 6 6 6-6"/>',
  file:'<path d="M8 3h8v11H8zM4 15v6h16v-6M9 17h6"/>'
})) document.querySelector('defs').insertAdjacentHTML('beforeend',`<symbol id="${id}" viewBox="0 0 24 24">${paths}</symbol>`);

const fixtureTimes=[['Jenny','2026-09-12T08:15:00+03:00','Jenny','2026-09-13T09:20:00+03:00'],['Pasi','2026-09-11T17:40:00+03:00','Jenny','2026-09-13T10:10:00+03:00'],['Pasi','2026-09-12T12:30:00+03:00','Pasi','2026-09-13T11:05:00+03:00'],['Jenny','2026-09-13T08:50:00+03:00','Jenny','2026-09-13T08:50:00+03:00']];
tasks.forEach((t,i)=>{[t.createdBy,t.createdAt,t.modifiedBy,t.modifiedAt]=fixtureTimes[i]});
function expedited(t){
  if(t.urgent||t.date==='Tänään')return true;
  if(!t.date)return false;
  const tomorrow=new Date();tomorrow.setDate(tomorrow.getDate()+1);
  const limit=[tomorrow.getFullYear(),String(tomorrow.getMonth()+1).padStart(2,'0'),String(tomorrow.getDate()).padStart(2,'0')].join('-');
  return /^\d{4}-\d{2}-\d{2}$/.test(t.date)&&t.date<=limit;
}
function initialPosition(t){if(!expedited(t))return tasks.length;const firstOrdinary=tasks.findIndex(x=>!expedited(x));return firstOrdinary<0?tasks.length:firstOrdinary}
function placementHint(){return editId?'Sijainti jonossa säilyy.':expedited(draft)?'Lisätään alkuun kiireellisten joukkoon. Tänään tai huomenna erääntyvät pääsevät myös alkuun.':'Lisätään jonon loppuun. Kiireelliset ja tänään tai huomenna erääntyvät menevät alkuun.'}
function updatePlacementHint(){const hint=$('#placement-hint');if(hint)hint.textContent=placementHint()}
function touchTask(t){t.modifiedBy='Pasi';t.modifiedAt=new Date().toISOString()}
function auditTime(value){return new Intl.DateTimeFormat('fi-FI',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value))}
function taskHistory(t){
  const line=(label,actor,time)=>`<div><dt>${label}</dt><dd>${esc(actor)} · <time datetime="${esc(time)}" title="${esc(new Intl.DateTimeFormat('fi-FI',{dateStyle:'full',timeStyle:'long'}).format(new Date(time)))}">${auditTime(time)}</time></dd></div>`;
  return `<dl class="task-history">${line('Luonut',t.createdBy,t.createdAt)}${t.modifiedAt!==t.createdAt||t.modifiedBy!==t.createdBy?line('Muuttanut',t.modifiedBy,t.modifiedAt):''}</dl>`;
}
function motionAllowed(){return animationsEnabled&&!matchMedia('(prefers-reduced-motion: reduce)').matches}
function notify(message,undo,effect){
  clearTimeout(toastTimer);
  const toast=$('#toast');toast.hidden=false;
  const rabbit=effect==='bow'||effect==='hop';
  const mark=effect?`<span class="action-mark ${motionAllowed()?'motion-'+effect:''}" aria-hidden="true">${rabbit?'<img src="bun-do-paper.svg" alt="">':icon(effect==='file'?'file':effect==='claim'?'people':'check')}</span>`:'';
  toast.innerHTML=`<span class="toast-message">${mark}<span>${esc(message)}</span></span>${undo?'<button>Kumoa</button>':''}`;
  if(undo)toast.querySelector('button').onclick=()=>{undo();toast.hidden=true};
  toastTimer=setTimeout(()=>toast.hidden=true,7000);
}
function toggleItem(id,i,v){const t=tasks.find(t=>t.id===id);t.list[i][1]=v;touchTask(t);render();const history=$('#sheet .task-history');if(history)history.outerHTML=taskHistory(t).split('<p')[0];}

function toggleReorder(){reorderMode=!reorderMode;filter='all';render();$('.reorder-toggle').focus()}
function reorderControls(t){const i=tasks.indexOf(t);return `<div class="reorder-controls"><button class="drag-handle" data-drag="${t.id}" aria-label="Siirrä: ${esc(t.title)}" aria-describedby="reorder-help">${icon('grip')}</button><div><button class="move-button" ${i===0?'disabled':''} aria-label="Siirrä ylemmäs: ${esc(t.title)}" onclick="moveTask(${t.id},-1)">${icon('up')}</button><button class="move-button" ${i===tasks.length-1?'disabled':''} aria-label="Siirrä alemmas: ${esc(t.title)}" onclick="moveTask(${t.id},1)">${icon('down')}</button></div></div>`}
function commitOrder(ids,movingId){
  if(ids.every((id,i)=>tasks[i]?.id===id))return false;
  const byId=new Map(tasks.map(t=>[t.id,t]));
  tasks=ids.map(id=>byId.get(id));touchTask(byId.get(movingId));
  return true;
}
function announceOrder(id){const position=tasks.findIndex(t=>t.id===id);$('#order-status').textContent=`${tasks[position].title}, sijainti ${position+1} / ${tasks.length}`}
function moveTask(id,delta){
  const i=tasks.findIndex(t=>t.id===id), to=i+delta;
  if(to<0||to>=tasks.length)return;
  const ids=tasks.map(t=>t.id);ids.splice(i,1);ids.splice(to,0,id);commitOrder(ids,id);
  render();document.querySelector(`[data-drag="${id}"]`).focus();announceOrder(id);
}
let dragSession=null;
function bindReorder(){
  document.querySelectorAll('[data-drag]').forEach(handle=>{
    handle.addEventListener('keydown',e=>{
      if(e.key==='ArrowUp'||e.key==='ArrowDown'){e.preventDefault();moveTask(Number(handle.dataset.drag),e.key==='ArrowUp'?-1:1)}
    });
    handle.addEventListener('pointerdown',e=>{
      if(e.button!==0||!e.isPrimary)return;
      e.preventDefault();
      const row=handle.closest('[data-row]');
      dragSession={handle,row,id:Number(handle.dataset.drag),pointer:e.pointerId,startY:e.clientY,y:e.clientY,active:false,frame:null};
      handle.setPointerCapture(e.pointerId);
    });
    handle.addEventListener('pointermove',e=>{
      const d=dragSession;if(!d||d.handle!==handle)return;
      d.y=e.clientY;
      if(!d.active&&Math.abs(d.y-d.startY)>6){d.active=true;d.row.classList.add('dragging');d.frame=requestAnimationFrame(dragFrame)}
    });
    handle.addEventListener('pointerup',()=>finishDrag(true));
    handle.addEventListener('pointercancel',()=>finishDrag(false));
    handle.addEventListener('lostpointercapture',()=>finishDrag(false));
  });
}
function dragFrame(){
  const d=dragSession;if(!d?.active)return;
  const content=$('#content'),bounds=content.getBoundingClientRect();
  if(d.y<bounds.top+48)content.scrollTop-=8;
  if(d.y>bounds.bottom-48)content.scrollTop+=8;
  const siblings=[...content.querySelectorAll('[data-row]')].filter(r=>r!==d.row);
  const before=siblings.find(row=>{const b=row.getBoundingClientRect();return d.y<b.top+b.height/2});
  content.insertBefore(d.row,before||null);
  d.frame=requestAnimationFrame(dragFrame);
}
function finishDrag(commit){
  const d=dragSession;if(!d)return;dragSession=null;cancelAnimationFrame(d.frame);
  if(commit&&d.active)commitOrder([...$('#content').querySelectorAll('[data-row]')].map(row=>Number(row.dataset.row)),d.id);
  if(d.handle.hasPointerCapture(d.pointer))d.handle.releasePointerCapture(d.pointer);
  render();document.querySelector(`[data-drag="${d.id}"]`)?.focus();
  if(commit&&d.active)announceOrder(d.id);
}
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&dragSession){e.preventDefault();finishDrag(false)}});
window.addEventListener('blur',()=>finishDrag(false));
$('#app').insertAdjacentHTML('beforeend','<p class="sr" id="reorder-help">Vedä ylös tai alas. Näppäimistöllä käytä nuolinäppäimiä. Escape peruu vetämisen.</p><p class="sr" id="order-status" role="status" aria-live="polite"></p>');
$('.intro .note').innerHTML='Try Järjestä to drag tasks into priority order. Open a task for its creator and timestamps. Finish a task, claim one, or add a new one to try the animations.<br><br>Design reference for native V1. This demo uses sample data and resets on refresh. AI and recording are simulated. Profile editing and combo effects stay in V2.';
render();
