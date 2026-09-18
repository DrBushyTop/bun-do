/* Throwaway, memory-only study. Native sync and completion policy are not implemented. */
const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const paths = {arrow:'m9 5 7 7-7 7',back:'m14 5-7 7 7 7',list:'M8 6h12M8 12h12M8 18h12M3 6h.01M3 12h.01M3 18h.01',bag:'M5 7h14l2 14H3L5 7ZM9 7V5a3 3 0 0 1 6 0v2',home:'m3 11 9-8 9 8M5 10v11h14V10M10 21v-7h4v7',pin:'m9 3 6 0-1 6 4 4v2H6v-2l4-4-1-6ZM12 15v7',plus:'M12 5v14M5 12h14',person:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM4 21v-2a8 8 0 0 1 16 0v2',check:'m5 12 4 4L19 6',book:'M4 3h16v18H4zM8 3v18M12 8h4M12 12h4'};
const icon = name => `<svg aria-hidden="true" viewBox="0 0 24 24"><path d="${paths[name] || paths.list}"/></svg>`;
const button = (text, action, cls='button', extra='') => `<button class="${cls}" data-action="${action}" ${extra}>${text}</button>`;
const seedItems = entries => entries.map(([text,note=''], i) => ({id:crypto.randomUUID(),text,note,done:false,claim:null,due:null}));
const cottage = () => seedItems([['Bed linen','Two sets, 160 cm fitted sheet'],['Towels','Two bath towels and sauna towels'],['Coffee','Ground coffee for the filter machine'],['Rain jackets'],['Phone chargers'],['Sauna supplies','Birch whisk and matches']]);
const starters = {
  packing:{title:'Cottage packing',icon:'home',description:'Choose what this trip needs.',items:cottage},
  cleaning:{title:'Weekly clean',icon:'home',description:'Start with the rooms that need attention.',items:()=>seedItems([['Kitchen surfaces'],['Bathroom'],['Vacuum floors'],['Fresh bed linen']])},
  hosting:{title:'Friends for dinner',icon:'person',description:'A short list before people arrive.',items:()=>seedItems([['Plan dinner','Ask about dietary needs'],['Buy ingredients'],['Set the table'],['Make room for coats']])},
  seasonal:{title:'Ready for winter',icon:'home',description:'Choose the jobs that fit your home.',items:()=>seedItems([['Check outdoor taps'],['Store garden furniture'],['Test smoke alarms'],['Find winter gloves']])}
};
const directions = {
 A:['Pinned beside tasks','Frequent lists sit above the queue. Everything else stays under All lists. Fast for two regular lists; many pins could crowd out tasks.'],
 B:['A place for lists','Lists is the fourth bottom destination beside Tasks, Activity and Together. Adventures stay under Together.'],
 C:['Start with an activity','Choose what you are doing, then use an existing list or start one. Helpful on first use; repeat visits take more navigation.']
};
let variant = new URLSearchParams(location.search).get('variant') || 'A';
if (!directions[variant]) variant='A';
let lists, saved, recent, screen, history, draft, sourceId, tab, undo;
let taskChecks = [false, false];
function reset(empty=false) {
 lists=empty?[]:[{id:'groceries',title:'Groceries',kind:'standing',pinned:true,notes:'Keep this list for the next shop, too.',items:seedItems([['Oat milk','2 cartons, unsweetened'],['Rye bread','Sliced'],['Tomatoes','500 g']])},{id:'last-trip',title:'Cottage weekend',kind:'fresh',pinned:false,notes:'The small cottage by the lake.',items:cottage().map(x=>({...x,done:true,due:'2026-08-21'}))}];
 saved=empty?[]:[{id:'cottage-saved',title:'Cottage packing',notes:'The small cottage by the lake.',items:cottage()}];
 recent=seedItems([['Eggs','6, free range'],['Oat milk','2 cartons, unsweetened'],['Apples','6, tart rather than sweet']]);
 screen='home';history=[];draft=null;sourceId=null;taskChecks=[false,false];tab='lists';undo=null;$('#notice').textContent='';render();
}
const current = () => lists.find(l=>l.id===sourceId);
function go(next,id=null){history.push({screen,sourceId,tab});screen=next;sourceId=id;render(true);}
function back(){const p=history.pop();if(p){({screen,sourceId,tab}=p);}else screen='home';draft=null;render(true);}
function tell(text){$('#notice').textContent=text;}
function row(title, sub, action, id='', name='list', color='') {
 return `<button class="row" data-action="${action}" data-id="${esc(id)}"><span class="cue ${color}">${icon(name)}</span><span class="words"><strong>${esc(title)}</strong><small>${esc(sub)}</small></span>${icon('arrow')}</button>`;
}
function listRow(l){const left=l.items.filter(i=>!i.done).length;return row(l.title,l.kind==='standing'?`${left} to buy · Stays open`:left?`${left} left`:'All done · Previous list','open',l.id,l.kind==='standing'?'bag':'home',l.kind==='standing'?'':'sand');}
function nav(){return `<nav class="nav-links app-nav" aria-label="App destinations">${[['tasks','Tasks','list'],['activity','Activity','check'],['together','Together','person'],['lists','Lists','book']].map(([id,label,glyph])=>button(`${icon(glyph)}<span>${label}</span>`,id,'',tab===id?'aria-current="page"':'')).join('')}</nav>`;}
function existingDestination(){return `<h2>${tab==='activity'?'Activity':'Together'}</h2><p class="lead">${tab==='activity'?'Shared activity stays in its existing destination.':"Adventures and Bun's journey stay here."}</p><p class="hint">Navigation preview only. This study does not recreate the existing ${tab==='activity'?'activity feed':'adventure screens'}.</p>${button('Open lists','lists','button outline full')}`;}

function tasks(){return `<div class="section"><div class="section-head"><h3>Tasks</h3><span>${taskChecks.filter(done=>!done).length} open</span></div>${['Book the bike service','Return library books'].map((t,i)=>`<div class="task"><input type="checkbox" id="task-${i}" data-task="${i}" ${taskChecks[i]?'checked':''} aria-label="Complete ${t}"><label for="task-${i}">${t}<small>${i?'This week':'Unclaimed'}</small></label></div>`).join('')}</div><p class="prototype-info">Task capture, Activity and Together stay unchanged. This study only exercises lists.</p>`;}
function library(){return `<h2>Our lists</h2><p class="lead">Keep the everyday ones. Start fresh for a new occasion.</p>${lists.length?lists.map(listRow).join(''):'<p class="hint">No lists yet. Start with something you do often.</p>'}<div class="actions">${button('New list','new')}</div><div class="section"><div class="section-head"><h3>Saved for next time</h3></div>${saved.length?saved.map(s=>row(s.title,`${s.items.length} items · Choose before starting`,'saved',s.id,'book')).join(''):'<p>Save any checklist here when you want to use it again.</p>'}</div>`;}
function home(){
 if(variant==='B') return tab==='tasks'?`<h2>Our tasks</h2>${tasks()}`:tab==='lists'?library():existingDestination();
 if(variant==='C') return `<h2 class="activity-prompt">What are we doing?</h2><p>Use a list you know, or let a starter help.</p><div class="activity-options">${row('Going shopping','Keep one shared grocery list','shopping','','bag')}${row('Packing for a trip','Choose from your saved packing list','packing','','home','sand')}${row('Looking after home','Cleaning, hosting and seasonal jobs','new','','list')}</div>${button('All lists','library','button outline full')}${tasks()}`;
 return `<h2>Our tasks</h2><p class="lead">A little room for what needs doing.</p><section class="pinned"><div class="section-head"><h3>Pinned lists</h3>${button('All lists','library','text-button')}</div>${lists.filter(l=>l.pinned).map(listRow).join('')||'<p>No pinned lists yet.</p>'}${!lists.length?button('Make your first list','new','text-button'):''}</section>${tasks()}`;
}
function newList(){return `<h2>What do you need a list for?</h2><p class="lead">Start with a few useful items, or write your own.</p>${row('Groceries','One shared list, ready for every shop','shopping','','bag')}${Object.entries(starters).map(([k,s])=>row(s.title,s.description,'starter',k,s.icon,'sand')).join('')}${row('Blank list','Name it and add your own items','blank','','plus')}${saved.length?`<div class="section"><h3>Saved for next time</h3>${saved.map(s=>row(s.title,`${s.items.length} items`,'saved',s.id,'book')).join('')}</div>`:''}`;}
function freshDraft(s,mode='copy') {draft={title:mode==='save'?s.title:`${s.title}${mode==='copy'?' · new list':''}`,notes:s.notes||'',kind:s.kind==='standing'?'standing':'fresh',items:s.items.map(i=>({...i,id:crypto.randomUUID(),done:false,claim:null,due:null,selected:true})),mode};go('preview');}
function preview(){return `<h2>${draft.mode==='save'?'Save for next time':'Choose what you need'}</h2><p class="lead">${draft.mode==='save'?'Save the items and notes, not the current checks. Future lists will be separate.':'This makes a separate list. The original keeps its items and notes.'}</p><label class="form-label" for="list-name">List name</label><input class="name-field" id="list-name" data-field="title" value="${esc(draft.title)}"><label class="form-label" for="list-notes">Notes</label><textarea class="name-field" id="list-notes" data-field="notes" rows="2">${esc(draft.notes)}</textarea><div class="selection-head"><span id="selected-count">${draft.items.filter(i=>i.selected).length} selected</span>${button('Select all','select-all','text-button')}</div>${draft.items.map(i=>`<label class="item"><input type="checkbox" data-select="${i.id}" ${i.selected?'checked':''}><span>${esc(i.text)}${i.note?`<small>${esc(i.note)}</small>`:''}</span></label>`).join('')}<form class="add-form" data-form="draft"><input aria-label="Add an item to this selection" name="text" placeholder="Add an item" required>${button('Add','submit','button','type="submit"')}</form><p class="saved-note">Items keep their order and notes. Checks, claims and old dates are cleared.</p><p class="field-error" id="draft-error" role="status"></p><div class="actions">${button(draft.mode==='save'?'Save reusable list':'Create list','create','button full',!draft.title.trim()||!draft.items.some(i=>i.selected)?'disabled':'')}${button('Cancel','back','button outline full')}</div>`;}
function detail(){const l=current();const open=l.items.filter(i=>!i.done).length;return `<div class="title-line"><h2>${esc(l.title)}</h2>${button(icon('pin'),'pin','icon-button',`aria-label="${l.pinned?'Unpin':'Pin'} ${esc(l.title)}" aria-pressed="${l.pinned}"`)}</div><p class="meta">${icon('person')}Shared with our household · ${open} ${l.kind==='standing'?'to buy':'left'}</p>${!open?`<div class="success">${l.kind==='standing'?'Everything bought. This list stays here for your next shop.':'All done. You can start a fresh copy next time.'}</div>`:''}${l.notes?`<p>${esc(l.notes)}</p>`:''}<form class="add-form" data-form="item"><input aria-label="Add an item" name="text" placeholder="Add an item" required>${button('Add','submit','button','type="submit"')}</form>${l.items.map(i=>`<div class="check-row"><label class="item ${i.done?'done':''}"><input type="checkbox" data-check="${i.id}" ${i.done?'checked':''}><span>${esc(i.text)}${i.note?`<small>${esc(i.note)}</small>`:''}${i.claim?'<small>You are taking this</small>':''}</span></label>${!i.done?button(i.claim?'Release':'I will take it','claim','text-button',`data-id="${i.id}" aria-label="${i.claim?'Release':'Take'} ${esc(i.text)}"`):''}</div>`).join('')}${l.kind==='standing'?`<details class="recent"><summary>Bought before</summary><p class="saved-note">Add it again with the same quantity and preferences.</p>${recent.map(i=>`<div class="again"><span>${esc(i.text)}<small>${esc(i.note)}</small></span>${button('Add again','again','text-button',`data-id="${i.id}" ${l.items.some(x=>!x.done&&x.text===i.text)?'disabled aria-label="Already on the list"':`aria-label="Add ${esc(i.text)} again"`}`)}</div>`).join('')}</details>`:''}<details class="secondary-actions"><summary>List options</summary>${button('Start a fresh copy','copy','text-button')}${button('Save for next time','save','text-button')}${button('Edit names, notes and order','edit','text-button')}${button('Delete list…','delete-confirm','text-button')}</details><p class="prototype-info">${l.kind==='standing'?'Study proposal: grocery checks do not finish this standing list.':'Study proposal: a fresh checklist finishes when every item is checked.'} Item claims are for yourself. Statistics and sync rules are not simulated.</p>`;}
function edit(){const l=current();return `<h2>Edit list</h2><p class="lead">Changes here affect this list only, not saved copies.</p><form data-form="edit"><label class="form-label" for="edit-title">List name</label><input required id="edit-title" class="name-field" name="title" value="${esc(l.title)}"><label class="form-label" for="edit-notes">Notes</label><textarea id="edit-notes" class="name-field" name="notes">${esc(l.notes)}</textarea>${l.items.map((i,n)=>`<div class="edit-item"><label class="form-label" for="text-${i.id}">Item ${n+1}</label><input class="name-field" id="text-${i.id}" name="text-${i.id}" required value="${esc(i.text)}"><label class="form-label" for="note-${i.id}">Item notes</label><input class="name-field" id="note-${i.id}" name="note-${i.id}" value="${esc(i.note)}"><label class="form-label" for="order-${i.id}">Position</label><input class="name-field" type="number" min="1" max="${l.items.length}" id="order-${i.id}" name="order-${i.id}" value="${n+1}"></div>`).join('')}<div class="actions"><button class="button" type="submit">Save changes</button>${button('Cancel','back','button outline','type="button"')}</div></form>`;}
function render(focus=false){
 $('#direction').innerHTML=`<h2>${variant}. ${directions[variant][0]}</h2><p>${directions[variant][1]}</p>`;
 $('#variant-label').innerHTML=`<strong>Option ${variant}</strong>${directions[variant][0]}`;
 let content=screen==='home'?home():screen==='library'?library():screen==='new'?newList():screen==='preview'?preview():screen==='detail'?detail():screen==='edit'?edit():`<h2>Delete ${esc(current().title)}?</h2><p class="lead">This removes the shared list for everyone in this demo. Saved reusable copies stay unchanged. Native retention and restore policy still need a decision.</p><div class="actions">${button('Delete list','delete')}${button('Keep list','back','button outline')}</div>`;
 $('#app').innerHTML=(screen!=='home'?button(`${icon('back')}Back`,'back','back'):'')+content;
 $('.phone').classList.toggle('bottom-tabs',variant==='B');
 $('.app-nav')?.remove();
 if(variant==='B')$('.phone').insertAdjacentHTML('beforeend',nav());
 $('#state').textContent=JSON.stringify({variant,screen,tab,lists,saved,draft},null,2);
 if(focus){const h=$('#app h2');if(h){h.tabIndex=-1;h.classList.add('screen-title');h.focus();h.scrollIntoView({block:'nearest'});}}
}
function switchVariant(delta){variant=['A','B','C'][(['A','B','C'].indexOf(variant)+delta+3)%3];const u=new URL(location.href);u.searchParams.set('variant',variant);window.history.replaceState(null,'',u);screen='home';history=[];draft=null;render(true);}
document.addEventListener('click', e=>{
 const b=e.target.closest('[data-action]');if(!b||b.disabled)return;const a=b.dataset.action,id=b.dataset.id;
 if(a==='submit')return;
 if(a==='next'||a==='previous')return switchVariant(a==='next'?1:-1);
 if(a==='back')return back();
 if(a==='reset'||a==='empty')return reset(a==='empty');
 if(a==='home'){screen='home';history=[];draft=null;return render(true);}
 if(['lists','tasks','activity','together'].includes(a)){tab=a;screen='home';history=[];draft=null;return render(true);}
 if(a==='library'||a==='new')return go(a);
 if(a==='open')return go('detail',id);
 if(a==='shopping') {const l=lists.find(l=>l.kind==='standing');if(l)return go('detail',l.id);draft={title:'Groceries',notes:'Keep this list for the next shop, too.',kind:'standing',items:[],mode:'new'};return go('preview');}
 if(a==='packing'){const packing=saved.find(s=>s.id==='cottage-saved');if(packing)return freshDraft(packing,'start');return freshDraft({title:starters.packing.title,items:cottage()},'start');}
 if(a==='starter'){const s=starters[id];return freshDraft({title:s.title,items:s.items()},'start');}
 if(a==='blank'){draft={title:'',notes:'',kind:'fresh',items:[],mode:'new'};return go('preview');}
 if(a==='saved')return freshDraft(saved.find(s=>s.id===id),'start');
 if(a==='copy'||a==='save')return freshDraft(current(),a);
 if(a==='select-all'){draft.items.forEach(i=>i.selected=true);return render();}
 if(a==='create'){
  const clean={id:crypto.randomUUID(),title:draft.title.trim(),notes:draft.notes.trim(),kind:draft.kind,pinned:false,items:draft.items.filter(i=>i.selected).map(({selected,...i})=>i)};
  if(!clean.title||!clean.items.length)return;
  if(draft.mode==='save'){saved.push(clean);back();tell('Saved for next time. Changes to this list will not change the saved copy.');}
  else{lists.push(clean);draft=null;history.pop();screen='detail';sourceId=clean.id;render(true);tell('Created a separate list. All items are unchecked.');}return;
 }
 if(a==='pin'){current().pinned=!current().pinned;render();tell(current().pinned?'Pinned for the household.':'Unpinned. Still available in All lists.');return;}
 if(a==='claim'){const i=current().items.find(i=>i.id===id);i.claim=i.claim?null:'You';render();return;}
 if(a==='again'){const i=recent.find(i=>i.id===id);current().items.push({...i,id:crypto.randomUUID(),done:false,claim:null,due:null});render();const d=$('.recent');if(d)d.open=true;tell(`${i.text} added with its notes.`);return;}
 if(a==='edit'||a==='delete-confirm')return go(a,sourceId);
 if(a==='delete'){undo=structuredClone(current());lists=lists.filter(l=>l.id!==sourceId);screen='library';history=[];render(true);$('#notice').innerHTML=`List deleted. ${button('Undo','undo','text-button')}`;return;}
 if(a==='undo'&&undo){lists.push(undo);sourceId=undo.id;undo=null;screen='detail';render(true);tell('List restored.');}
});
document.addEventListener('change',e=>{
 if(e.target.dataset.task!==undefined){taskChecks[Number(e.target.dataset.task)]=e.target.checked;render();return;}
 if(e.target.id==='large-text'){$('.phone').classList.toggle('large',e.target.checked);return;}
 if(e.target.dataset.check){const i=current().items.find(i=>i.id===e.target.dataset.check);i.done=e.target.checked;i.claim=null;if(i.done&&current().kind==='standing'&&!recent.some(r=>r.text===i.text&&r.note===i.note))recent.unshift({...i});render();}
 if(e.target.dataset.select){draft.items.find(i=>i.id===e.target.dataset.select).selected=e.target.checked;refreshDraft();}
});
function refreshDraft(){const n=draft.items.filter(i=>i.selected).length;$('#selected-count').textContent=`${n} selected`;document.querySelector('[data-action=create]').disabled=!draft.title.trim()||!n;$('#draft-error').textContent=!draft.title.trim()?'Give the list a name.':!n?'Select or add at least one item.':'';$('#state').textContent=JSON.stringify({variant,screen,lists,saved,draft},null,2);}
document.addEventListener('input',e=>{if(e.target.dataset.field){draft[e.target.dataset.field]=e.target.value;refreshDraft();}});
document.addEventListener('submit',e=>{
 const form=e.target;if(!form.dataset.form)return;e.preventDefault();const f=new FormData(form);
 if(form.dataset.form==='edit'){const l=current();if(!f.get('title').trim()||l.items.some(i=>!f.get(`text-${i.id}`).trim()))return;l.title=f.get('title').trim();l.notes=f.get('notes').trim();l.items=l.items.map(i=>({...i,text:f.get(`text-${i.id}`).trim(),note:f.get(`note-${i.id}`).trim(),position:Number(f.get(`order-${i.id}`))})).sort((a,b)=>a.position-b.position);back();tell('Changes saved to this list only.');return;}
 const text=f.get('text').trim();if(!text)return;
 const i=seedItems([[text]])[0];if(form.dataset.form==='draft')draft.items.push({...i,selected:true});else current().items.push(i);
 render();const input=$(`form[data-form="${form.dataset.form}"] input`);input?.focus();tell(`${text} added.`);
});
document.addEventListener('keydown',e=>{if(e.target.closest('input,textarea,select,[contenteditable],summary'))return;if(e.key==='ArrowRight'||e.key==='ArrowLeft'){e.preventDefault();switchVariant(e.key==='ArrowRight'?1:-1);}if(e.key==='Escape'&&screen!=='home')back();});
reset();
