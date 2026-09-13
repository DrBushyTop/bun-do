// Optional V3 concept. No XP, scheduling authority, location data or AI calls.
let worldEnabled=new URLSearchParams(location.search).get('journey')==='1';
let moodPreview='auto', celebrationUntil=0, worldTimer;
let selectedQuest=null;
const questOptions=[
  {id:'room',title:'Varaston koetus',description:'Tila selkenee, yksi harjoitus kerrallaan.',taskIds:[2],difficulty:3,minutes:90,estimateSource:'sample',art:'dojo-storage.webp',reason:'Neljä vaihetta, tavaroiden lajittelua ja päätöksiä. Voitte tehdä yhden vaiheen kerrallaan.',phaseNames:['Työkalujen kata','Pahvivuoren valloitus','Hyllyjen salaisuudet','Luopumisen taito']},
  {id:'round',title:'Kylän kierros',description:'Kaksi arkista asiaa. Yksi pieni seikkailu.',taskIds:[3,4],difficulty:1,minutes:30,estimateSource:'sample',art:'dojo-garden.webp',reason:'Kaksi lyhyttä tehtävää. Matka-aikaa ei tunneta, joten arvio voi muuttua.',phaseNames:['Kevyemmin matkaan','Valoa kotiin']}
];
let lastQuestProgress=null,questFinishedUntil=0,questFinishToken=0,headerFinishSeen=0,detailFinishSeen=0;
function questPhases(q){
  return q.taskIds.flatMap(id=>{
    const task=tasks.find(t=>t.id===id);
    if(task?.list?.length)return task.list.map((item,i)=>({taskId:id,itemIndex:i,title:q.phaseNames[i]||item[0],taskTitle:item[0],done:item[1],available:true}));
    return [{taskId:id,itemIndex:null,title:q.phaseNames[q.taskIds.indexOf(id)]||task?.title||'Tehtävä',taskTitle:task?.title||(worldDone.has(id)?'Hoidettu tehtävä':'Tehtävä ei ole saatavilla'),done:worldDone.has(id),available:!!task}];
  });
}
function questProgress(q){const phases=questPhases(q);return {done:phases.filter(p=>p.done).length,total:phases.length,phases}}
function observeQuestProgress(){
  if(!selectedQuest){lastQuestProgress=null;return}
  const p=questProgress(selectedQuest),complete=p.total>0&&p.done===p.total;
  if(lastQuestProgress?.id===selectedQuest.id&&!lastQuestProgress.complete&&complete){questFinishedUntil=Date.now()+1800;questFinishToken++}
  if(!complete)questFinishedUntil=0;
  lastQuestProgress={id:selectedQuest.id,complete};
}
function difficultyMarkup(q){return `<span class="adventure-difficulty" aria-label="Vaativuus ${q.difficulty} / 3"><span aria-hidden="true">${[1,2,3].map(i=>`<svg class="${i<=q.difficulty?'filled':''}"><use href="#quest-star"/></svg>`).join('')}</span>Vaativuus ${q.difficulty} / 3</span><span>${icon('clock')}noin ${q.minutes} min</span>`}
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
  observeQuestProgress();
  const active=worldEnabled&&home&&!reorderMode,top=$('#world-top');
  top.classList.toggle('world-active',active);
  $('#world-header-art').hidden=!active;
  $('#world-quest').hidden=!active;
  if(!active){top.style.backgroundImage='none';return}
  const mood=worldMood();
  top.classList.toggle('world-motion-off',!motionAllowed());
  top.style.backgroundImage=`linear-gradient(180deg,#f8f9f4ed 0%,#f8f9f488 30%,#f8f9f400 55%,#f8f9f433 90%,#f8f9f4 100%),url(art/${mood==='journey'?'dojo-garden.webp':mood+'-header-royal.webp'})`;
  $('#world-header-art').innerHTML='';
  $('#world-header-art').className=mood==='joy'&&motionAllowed()?'world-joy':'';
  const key=selectedQuest?.id||'none',host=$('#world-quest');
  if(host.dataset.quest!==key){
    host.dataset.quest=key;
    host.innerHTML=`<button class="quest-sign" onclick="openQuests()"><span class="quest-sign-icon">${icon('quest-scroll')}</span><span class="quest-sign-copy"><span class="quest-sign-title"></span><span class="quest-sign-status"></span><span class="quest-sign-track" aria-hidden="true"><span></span></span></span>${icon('arrow')}</button>`;
  }
  const button=host.querySelector('button'),stats=selectedQuest?questProgress(selectedQuest):null;
  host.querySelector('.quest-sign-title').textContent=selectedQuest?.title||'Valitse yhteinen seikkailu';
  host.querySelector('.quest-sign-status').textContent=stats?(stats.done===stats.total?'Seikkailu valmis':`${stats.done} / ${stats.total} vaihetta hoidettu`):'Pieni harjoitus arjen keskellä';
  host.querySelector('.quest-sign-track').hidden=!stats;
  host.querySelector('.quest-sign-track>span').style.transform=`scaleX(${stats?.total?stats.done/stats.total:0})`;
  button.setAttribute('aria-label',selectedQuest?`${selectedQuest.title}, ${stats.done} / ${stats.total} vaihetta hoidettu. Avaa seikkailu.`:'Valitse yhteinen seikkailu');
  const finishing=!!stats&&stats.total>0&&stats.done===stats.total&&questFinishToken>headerFinishSeen&&$('#overlay').hidden;
  button.classList.toggle('quest-is-done',!!stats&&stats.total>0&&stats.done===stats.total);
  if(finishing){
    headerFinishSeen=questFinishToken;
    button.dataset.finishToken=String(questFinishToken);
    if(motionAllowed()){button.classList.remove('quest-finish');void button.offsetWidth;button.classList.add('quest-finish')}
    $('#quest-live').textContent='Seikkailu valmis. Hyvin harjoiteltu.';
  }

}

function toggleWorld(){worldEnabled=!worldEnabled;closeSheet();render();const url=new URL(location.href);url.searchParams.set('journey',worldEnabled?'1':'0');history.replaceState(null,'',url)}
function celebrateWorld(id){worldDone.add(id);celebrationUntil=Date.now()+1200;clearTimeout(worldTimer);worldTimer=setTimeout(()=>{celebrationUntil=0;if(view==='tasks'&&$('#overlay').hidden)render()},1250)}
function undoWorld(id){worldDone.delete(id);celebrationUntil=0}
function openWorld(){
  openSheet(head('Bunin oma pieni maailma')+`<p>V3-kokeilu. Yhteinen seikkailu ilman pisteitä, kiirettä tai rangaistuksia.</p><p class="quiet">Bun lepää, kun lista on tyhjä. Kuusi myöhässä olevaa tehtävää tuo esiin rauhallisen paperihetken. Valmistuminen tuo pienen ilon loikan.</p><p class="quiet">Seikkailun palkki seuraa sen tehtäviä ja vaiheita. Tähdet ovat arvio työn määrästä, eivät pisteitä.</p><h3>Kokeile tunnelmaa</h3><div class="mood-options">${[['auto','Tehtävien mukaan'],['rest','Riippumatto'],['paperwork','Paperihetki'],['joy','Ilon loikka'],['journey','Dojon piha']].map(([m,label])=>`<button onclick="moodPreview='${m}';closeSheet()" aria-pressed="${moodPreview===m}">${label}</button>`).join('')}</div><p class="ai-note">Tunnelman kokeilu ei muuta tehtäviä. Tämä ei ole osa V1:n julkaisuehtoja.</p><button class="cancel" onclick="toggleWorld()">Piilota Bun-maailma</button>`);
}
function openQuests(){
  if(selectedQuest){showQuest();return}
  openSheet(head('Yhteiset seikkailut')+`<p class="adventure-intro">Arki on harjoitusta. Valitse teille sopiva seikkailu.</p><p class="ai-note">V3-luonnos. Nimet, tähdet ja ajat ovat esimerkkejä tulevista tekoälyehdotuksista.</p>${questOptions.map(q=>{const p=questProgress(q);return `<section class="quest-option adventure-option" style="--adventure-art:url(art/${q.art})"><h3>${esc(q.title)}</h3><p>${esc(q.description)}</p><div class="adventure-meta">${difficultyMarkup(q)}</div><p class="quiet">${p.done} / ${p.total} vaihetta hoidettu</p><button class="write" onclick="previewQuest('${q.id}')">Tutustu seikkailuun ${icon('arrow')}</button></section>`}).join('')}<button class="cancel" onclick="closeSheet()">Ei nyt</button>`);
}
function previewQuest(id){showAdventure(questOptions.find(q=>q.id===id),false)}
function acceptQuest(id){selectedQuest=questOptions.find(q=>q.id===id);headerFinishSeen=questFinishToken;detailFinishSeen=questFinishToken;lastQuestProgress=null;questFinishedUntil=0;render();showQuest()}
function leaveQuest(){selectedQuest=null;questFinishedUntil=0;lastQuestProgress=null;closeSheet()}
function showQuest(){if(selectedQuest)showAdventure(selectedQuest,true)}
function showAdventure(q,active){
  const p=questProgress(q),done=p.total>0&&p.done===p.total,finishing=active&&done&&questFinishToken>detailFinishSeen;
  if(finishing)detailFinishSeen=questFinishToken;
  openSheet(`<div class="adventure-hero" style="--adventure-art:url(art/${q.art})">${head(q.title)}<p>${esc(q.description)}</p><div class="adventure-meta">${difficultyMarkup(q)}</div></div><details class="estimate-details"><summary>${q.estimateSource==='sample'?'Esimerkki tekoälyarviosta':'Oma arvio'} · muokkaa</summary><p class="quiet">${esc(q.reason)} Tähdet kuvaavat työn määrää, eivät taitojanne. Arvio ei anna pisteitä.</p><form onsubmit="saveQuestEstimate(event,'${q.id}',${active})"><label for="quest-difficulty">Vaativuus</label><select id="quest-difficulty">${[1,2,3].map(n=>`<option value="${n}" ${q.difficulty===n?'selected':''}>${n} / 3</option>`).join('')}</select><label for="quest-minutes">Arvioitu aika minuutteina</label><input id="quest-minutes" type="number" min="1" step="1" required value="${q.minutes}"><button class="write" type="submit">Tallenna oma arvio</button></form></details><div class="adventure-progress"><div><span>${done?'Seikkailu valmis':`${p.done} / ${p.total} vaihetta hoidettu`}</span><span>${Math.round(p.done/Math.max(1,p.total)*100)} %</span></div><progress value="${p.done}" max="${p.total||1}" aria-label="Seikkailun eteneminen"></progress></div>${done?`<div class="adventure-complete ${finishing&&motionAllowed()?'adventure-bow':''}" role="status"><img src="bun-do.svg" alt=""><span>Hyvin harjoiteltu.<small>Nyt voi ottaa hetken rauhassa.</small></span></div>`:''}<ol class="adventure-phases">${p.phases.map((phase,i)=>`<li class="${phase.done?'phase-done':''}"><span class="phase-mark" aria-hidden="true">${phase.done?icon('check'):i+1}</span><div><strong>${esc(phase.title)}</strong><span class="phase-source">${esc(phase.taskTitle)}</span>${active&&phase.available?phase.itemIndex!==null?`<label class="phase-action"><input type="checkbox" data-phase="${i}" ${phase.done?'checked':''} onchange="toggleQuestPhase(${phase.taskId},${phase.itemIndex},this.checked,${i})">${phase.done?'Tehty':'Merkitse vaihe tehdyksi'}</label>`:`<button class="phase-open" onclick="detail(${phase.taskId})">Avaa tehtävä ${icon('arrow')}</button>`:''}</div></li>`).join('')}</ol><p class="ai-note">Vaiheet ovat samoja tehtäviä ja muistilistan kohtia. Seikkailu ei muuta niiden järjestystä tai määräpäiviä.</p>${active?'<button class="cancel" onclick="closeSheet()">Takaisin tehtäviin</button><button class="cancel" onclick="leaveQuest()">Jätä seikkailu</button>':`<button class="primary" onclick="acceptQuest('${q.id}')">Valitse tämä seikkailu ${icon('quest-scroll')}</button><button class="cancel" onclick="openQuests()">Takaisin ehdotuksiin</button>`}`);
}
function toggleQuestPhase(id,index,done,phaseIndex){toggleItem(id,index,done);showQuest();$('#sheet [data-phase="'+phaseIndex+'"]')?.focus()}
function saveQuestEstimate(event,id,active){event.preventDefault();const q=questOptions.find(q=>q.id===id);q.difficulty=Number($('#quest-difficulty').value);q.minutes=Number($('#quest-minutes').value);q.estimateSource='human';showAdventure(q,active)}
for(const [id,path]of Object.entries({'quest-star':'<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9z"/>','quest-scroll':'<path d="M6 3h12a3 3 0 0 1 0 6h-1M6 3a3 3 0 0 0 0 6h1V3m0 6v9a3 3 0 0 0 6 0H3a3 3 0 0 0 3 3h11V6M10 11h4m-4 3h4"/>'}))document.querySelector('defs').insertAdjacentHTML('beforeend',`<symbol id="${id}" viewBox="0 0 24 24">${path}</symbol>`);
$('#app').insertAdjacentHTML('beforeend','<p id="quest-live" class="sr" role="status"></p>');
$('.intro').insertAdjacentHTML('beforeend','<button class="world-demo-toggle" onclick="toggleWorld()">Näytä / piilota Bun-maailma · V3</button>');

const worldTop=document.createElement('div');worldTop.id='world-top';
$('#app').prepend(worldTop);worldTop.append($('.top'),$('#filters'));
worldTop.insertAdjacentHTML('beforeend','<div id="world-header-art" aria-hidden="true" hidden></div><div id="world-quest" hidden></div>');
