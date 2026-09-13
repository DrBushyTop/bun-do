// Optional V3 concept. No XP, scheduling authority, location data or AI calls.
let worldEnabled=new URLSearchParams(location.search).get('journey')==='1';
let moodPreview='auto', celebrationUntil=0, worldTimer;
let selectedQuest=null;
const questOptions=[
  {id:'room',title:'Tilaa pienille seikkailuille',description:'Tehdään kotona vähän tilaa. Ei kiirettä.',taskIds:[2,3]},
  {id:'round',title:'Pieni asiointiretki',description:'Pari asiaa samalla kierroksella, jos se sopii päivään.',taskIds:[3,4]}
];
const worldDone=new Set();
function worldMood(){
  if(moodPreview!=='auto')return moodPreview;
  if(Date.now()<celebrationUntil)return 'joy';
  if(!tasks.length)return 'rest';
  const now=new Date(),today=[now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),String(now.getDate()).padStart(2,'0')].join('-');
  const overdue=tasks.filter(t=>/^\d{4}-\d{2}-\d{2}$/.test(t.date||'')&&t.date<today).length;
  return overdue>=6?'paperwork':'journey';
}
function journeyMarkup(){return ''}
function updateWorldHeader(home){
  const active=worldEnabled&&home&&!reorderMode,top=$('#world-top');
  top.classList.toggle('world-active',active);
  $('#world-header-art').hidden=!active;
  $('#world-quest').hidden=!active;
  if(!active){top.style.backgroundImage='none';return}
  const mood=worldMood(),stage=Math.min(4,worldDone.size);
  top.style.backgroundImage=`linear-gradient(180deg,#f8f9f4ed 0%,#f8f9f488 30%,#f8f9f400 55%,#f8f9f433 90%,#f8f9f4 100%),url(art/${mood}-header${mood==='journey'?'':'-royal'}.webp)`;
  $('#world-header-art').innerHTML=mood==='journey'?`<img class="bun-walker" src="bun-do.svg" alt="" style="left:${8+stage*19}%">`:'';
  $('#world-header-art').className=mood==='joy'&&motionAllowed()?'world-joy':'';
  $('#world-quest').innerHTML=`<button class="quest-sign" onclick="openQuests()"><span class="quest-sign-icon">${icon('leaf')}</span><span>${selectedQuest?esc(selectedQuest.title):'Lähdetään pienelle retkelle'}</span>${icon('arrow')}</button>`;
}

function toggleWorld(){worldEnabled=!worldEnabled;closeSheet();render();const url=new URL(location.href);url.searchParams.set('journey',worldEnabled?'1':'0');history.replaceState(null,'',url)}
function celebrateWorld(id){worldDone.add(id);celebrationUntil=Date.now()+1200;clearTimeout(worldTimer);worldTimer=setTimeout(()=>{celebrationUntil=0;if(view==='tasks'&&$('#overlay').hidden)render()},1250)}
function undoWorld(id){worldDone.delete(id);celebrationUntil=0}
function openWorld(){
  openSheet(head('Bunin oma pieni maailma')+`<p>V3-kokeilu. Yhteinen retki ilman pisteitä, kiirettä tai rangaistuksia.</p><p class="quiet">Bun lepää, kun lista on tyhjä. Kuusi myöhässä olevaa tehtävää tuo esiin rauhallisen paperihetken. Valmistuminen tuo pienen ilon loikan.</p><p class="quiet">Retki etenee vain ensimmäisistä valmistumisista. Tehtävien koko tai tekijä ei vaikuta siihen.</p><h3>Kokeile tunnelmaa</h3><div class="mood-options">${[['auto','Tehtävien mukaan'],['rest','Riippumatto'],['paperwork','Paperihetki'],['joy','Ilon loikka'],['journey','Puutarhapolku']].map(([m,label])=>`<button onclick="moodPreview='${m}';closeSheet()" aria-pressed="${moodPreview===m}">${label}</button>`).join('')}</div><p class="ai-note">Tunnelman kokeilu ei muuta tehtäviä. Tämä ei ole osa V1:n julkaisuehtoja.</p><button class="cancel" onclick="toggleWorld()">Piilota Bun-maailma</button>`);
}
function openQuests(){
  if(selectedQuest){showQuest();return}
  openSheet(head('Pieni yhteinen retki')+`<p>Valitse ehdotus, jos se sopii teille. Tavalliset tehtävät pysyvät omilla paikoillaan.</p><p class="ai-note">V3-kokeilu · nämä ovat valmiita esimerkkejä, eivät tekoälyn tuloksia.</p>${questOptions.map(q=>`<section class="quest-option"><h3>${esc(q.title)}</h3><p class="quiet">${esc(q.description)}</p><ul>${q.taskIds.map(id=>`<li>${esc(tasks.find(t=>t.id===id)?.title||'Jo hoidettu tehtävä')}</li>`).join('')}</ul><button class="write" onclick="selectedQuest=questOptions.find(q=>q.id==='${q.id}');showQuest()">Valitse tämä retki</button></section>`).join('')}<button class="cancel" onclick="closeSheet()">Ei nyt</button>`);
}
function showQuest(){
  const q=selectedQuest,done=q.taskIds.filter(id=>worldDone.has(id)).length;
  openSheet(head(q.title)+`<p>${esc(q.description)}</p><p class="quiet">${done} / ${q.taskIds.length} hoidettu. Ei määräaikaa.</p><div class="quest-tasks">${q.taskIds.map(id=>{const task=tasks.find(t=>t.id===id);return task?`<button onclick="detail(${id})">${icon('tasks')}${esc(task.title)}${icon('arrow')}</button>`:`<p>${icon('check')}${worldDone.has(id)?'Hoidettu':'Tehtävä ei ole saatavilla'}</p>`}).join('')}</div><p class="ai-note">Retki viittaa olemassa oleviin tehtäviin. Poistuminen ei poista tai järjestä niitä.</p><button class="cancel" onclick="selectedQuest=null;closeSheet()">Jätä retki</button>`);
}
$('.intro').insertAdjacentHTML('beforeend','<button class="world-demo-toggle" onclick="toggleWorld()">Näytä / piilota Bun-maailma · V3</button>');

const worldTop=document.createElement('div');worldTop.id='world-top';
$('#app').prepend(worldTop);worldTop.append($('.top'),$('#filters'));
worldTop.insertAdjacentHTML('beforeend','<div id="world-header-art" aria-hidden="true" hidden></div><div id="world-quest" hidden></div>');
