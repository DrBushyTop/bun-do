/* THROWAWAY #70. Three artwork layouts within the selected read-first task design, ?variant=A|B|C.
   No microphone, AI, persistence, network requests or real task mutations.
   Voice revision is proposed; explicit initial checklist capture already exists for shared tasks. */
const params = new URLSearchParams(location.search);
let variant = ['A','B','C'].includes(params.get('variant')) ? params.get('variant') : 'A';
let screen = ['queue','task','checklist','review'].includes(params.get('screen')) ? params.get('screen') : 'task';
let lang = params.get('lang') === 'en' ? 'en' : 'fi';
let compare = params.get('mode') !== 'focus';
let large = params.get('text') === 'large';
const t = (fi,en) => lang === 'fi' ? fi : en;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const paths = {
  back:'<path d="m12 5-7 7 7 7M5 12h15"/>', more:'<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
  close:'<path d="m6 6 12 12M18 6 6 18"/>', next:'<path d="m9 5 7 7-7 7"/>',
  check:'<path d="m5 12 4 4L19 6"/>', box:'<rect x="4" y="4" width="16" height="16" rx="3"/>',
  edit:'<path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14z"/>',
  mic:'<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M9 22h6"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  list:'<path d="M9 6h12M9 12h12M9 18h12M3 6h1M3 12h1M3 18h1"/>',
  info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7v.5"/>',
  person:'<circle cx="12" cy="7" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  undo:'<path d="M4 4v6h6M4 10a8 8 0 1 1 0 6"/>',
  trash:'<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',
  glasses:'<circle cx="6" cy="14" r="4"/><circle cx="18" cy="14" r="4"/><path d="M10 14h4M2 14l2-8M22 14l-2-8"/>',
  bike:'<circle cx="5" cy="17" r="4"/><circle cx="19" cy="17" r="4"/><path d="m5 17 5-10 5 10H5m5-10 9 10m-3-13h3v4M8 7h4"/>',
  archive:'<path d="M3 7h18v4H3zM5 11v10h14V11M9 15h6M6 3h12v4"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  heart:'<path d="M20 5a5 5 0 0 0-8 1 5 5 0 0 0-8-1c-6 6 8 15 8 15S26 11 20 5Z"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.more}</svg>`;
const button = (label,action,style='',glyph='',disabled=false) => `<button class="button ${style}" data-action="${action}" ${disabled?'disabled':''}>${glyph?icon(glyph):''}${esc(label)}</button>`;
const ib = (name,label,action) => `<button class="icon-button" aria-label="${esc(label)}" data-action="${action}">${icon(name)}</button>`;
const row = (label,action,glyph='next',hint='',danger=false) => `<button class="navrow ${danger?'danger-text':''}" data-action="${action}">${icon(glyph)}<span>${esc(label)}${hint?`<small>${esc(hint)}</small>`:''}</span>${icon('next')}</button>`;
const sampleTitle = () => t('Selvitä mikrokuituliinojen saatavuus Napolissa','Find microfibre cloths in Naples');
const sampleNote = () => t('Selvitä huomenna, mistä Napolista saa isoja mikrokuituliinoja silmälaseja varten.','Find out tomorrow where to get large microfibre cloths for glasses in Naples.');
const sampleSteps = () => [t('Etsi lähistön optikkoliikkeet','Find nearby opticians'),t('Kysy isojen liinojen saatavuutta','Ask whether they stock large cloths'),t('Vertaa kokoja ja hintoja','Compare sizes and prices')];
const taskSamples = () => [
  {key:'cloth', title:sampleTitle(), note:sampleNote(), image:'cloth-royal.webp', glyph:'glasses', tint:'gold'},
  {key:'bike', title:t('Varaa pyörähuolto','Book bike service'), note:t('Pyydä tarkistamaan jarrut ja vaihtamaan kuluneet jarrupalat.','Ask them to check the brakes and replace worn brake pads.'),image:'bike-royal.webp',glyph:'bike',tint:'blue'},
  {key:'storage',title:t('Vie pahvit kierrätykseen','Recycle the cardboard'),note:t('Litistä eteisen laatikot ja vie ne paperinkeräyksen viereiseen kartonkiastiaan.','Flatten the boxes in the hall and take them to cardboard recycling.'),image:'storage-royal.webp',glyph:'archive',tint:'lavender'},
];
// One existing adventure scene in every option makes crop and placement comparable.
const scene = (kind='scene-image') => `<img class="${kind}" src="assets/dojo-garden.webp" alt="">`;
function taskOpening(s,v,review=false) {
  const nav=bar(review?t('Tarkista ehdotus','Review suggestion'):t('Tehtävä','Task'),review?'leave-review':'queue',review?'review-menu':'menu',review);
  const title=`${review?`<p class="muted draft-label">${esc(t('Ei vielä tallennettu','Not saved yet'))}</p>`:''}<h2 class="task-title">${esc(s.title)}</h2>`;
  if(v==='A') return `<div class="image-scroll"><div class="panorama">${scene()}${nav}</div><div class="task-reading">${title}`;
  if(v==='B') return `${nav}<div class="image-scroll"><div class="task-reading title-first">${title}<figure class="whole-scene">${scene()}</figure>`;
  return `${nav}<div class="image-scroll"><div class="task-reading"><div class="split-opening"><div>${title}</div><div class="portrait-scene">${scene()}</div></div>`;
}
function initial() {
  return {taskKey:'cloth',view:screen==='queue'?'queue':'detail',title:sampleTitle(),note:sampleNote(),items:screen==='checklist'?sampleSteps():[],checked:[],
    menu:null,claimed:false,due:'',snooze:'',repeat:'',position:'',done:false,deleted:false,cancelled:false,
    dirty:false,toast:'',undo:null,history:[],sample:'add',error:false,offline:false,pending:null,revisionCount:0,saved:false};
}
let states = {A:initial(),B:initial(),C:initial()};
let taskMemory = {A:{},B:{},C:{}};
const variants = () => ({
  A:{name:t('Häipyvä maisema','Faded scene'),note:t('220 px korkea maisema ennen otsikkoa. Kuva häipyy paperiin, mutta sen keskiosa säilyy selkeänä.','A 220px scene above the title. Only the edges fade, leaving the illustration visible.')},
  B:{name:t('Otsikko ensin','Title first'),note:t('Nimi ensin, kokonainen kuva sen alla. Ei rajausta eikä tekstiä kuvan päällä.','Title first, then the complete picture. No crop and no text over the artwork.')},
  C:{name:t('Kuva rinnalla','Picture alongside'),note:t('Otsikko ja pystysuuntainen kuvarajaus rinnakkain. Kuva ei työnnä sisältöä alemmas.','Title beside a tall crop. The image shares the title’s space rather than pushing everything down.')},
});
function bar(title,left='queue',right='menu',edit=false) {
  return `<header class="appbar">${ib(left==='close-sheet'?'close':'back',t('Takaisin listalle','Back to list'),left)}<strong>${esc(title)}</strong>${edit?ib('edit',t('Muokkaa tehtävää','Edit task'),'edit'):''}${right?ib('more',t('Tehtävän valikko','Task menu'),right):''}</header>`;
}
function owner(s) { return `<div class="status-row">${s.items.length?`<span class="muted">${esc(t('Yhteinen tehtävä · tee vaihe kerrallaan','Shared task · complete one step at a time'))}</span>`:`<button class="status-button" data-action="claim">${icon('person')}${esc(s.claimed?t('Työn alla: minä','Claimed by me'):t('Vapaa · Otan työn alle','Unclaimed · I’ll do it'))}</button>`}${s.due?`<span class="muted">${esc(s.due)}</span>`:''}${s.snooze?`<span class="muted">${esc(t('Lykätty: ','Snoozed: ')+s.snooze)}</span>`:''}</div>`; }
function checklist(s,draft=false) {
  if (!s.items.length) return '';
  return `<section class="steps"><h3>${esc(t('Vaiheet','Steps'))}${!draft?` · ${s.checked.length}/${s.items.length}`:''}</h3>${s.items.map((item,i)=>draft?
    `<div class="draft-step"><small>${i+1}</small><span>${esc(item)}</span></div>`:
    `<label class="step"><input type="checkbox" data-step="${i}" ${s.checked.includes(i)?'checked':''}><span>${esc(item)}</span></label>`).join('')}</section>`;
}
function toast(s) { return s.toast?`<div class="toast" role="status"><span>${esc(s.toast)}</span>${s.undo?`<button class="text-button" data-action="undo">${esc(t('Kumoa','Undo'))}</button>`:''}</div>`:''; }
function completeButton(s) { return s.items.length?`<p class="muted">${esc(t('Tehtävä valmistuu, kun kaikki vaiheet on tehty.','Complete each step to finish this task.'))}</p>`:button(t('Merkitse valmiiksi','Mark complete'),'complete','grow','check'); }
function focusView(s,v='A') {
  return `${taskOpening(s,v)}<p class="task-description">${esc(s.note)}</p>${owner(s)}${checklist(s)}</div></div>${toast(s)}<footer class="footer"><div class="actions">${button(t('Muokkaa','Edit'),'edit','secondary')}${completeButton(s)}</div></footer>`;
}
function queueBase(s,open=false,v=variant) {
  const samples=taskSamples().map(x=>({...x,...(x.key===s.taskKey?s:taskMemory[v][x.key]||{})}));
  return `<div class="queue-background" ${open?'inert':''}><div class="queue-scene"><header class="appbar"><img class="brand" src="assets/bun.svg" alt=""><strong>Bun Do</strong>${ib('more',t('Listan toiminnot','Queue actions'),'queue-menu')}</header>
    <button class="adventure-entry" data-action="adventure-demo">${icon('list')}<span>${esc(t('Seikkailu','Adventure'))}</span>${icon('next')}</button></div>
    <div class="queue-content"><div class="queue-count"><strong>${esc(t('3 tehtävää','3 tasks'))}</strong><button class="text-button" data-action="queue-menu">${esc(t('Kaikki','All'))}${icon('next')}</button></div>
    ${samples.map(task=>`<button class="visual-task" data-action="open-task:${task.key}"><span class="category ${task.tint}">${icon(task.glyph)}</span><span class="task-copy"><strong>${esc(task.title)}</strong>${task.items?.length?`<small>${task.checked.length}/${task.items.length} ${esc(t('vaihetta','steps'))}</small>`:''}</span>${icon('next')}</button>`).join('')}
    </div><div class="capture-dock">${button(t('Kirjoita','Type'),'new-demo','secondary grow','plus')}${button(t('Puhu tehtävä','Speak a task'),'capture-demo','grow','mic')}</div>
    <nav class="app-tabs" aria-label="${esc(t('Päänavigaatio','Main navigation'))}"><button class="selected" data-action="queue">${icon('list')}<span>${esc(t('Tehtävät','Tasks'))}</span></button><button data-action="activity-demo">${icon('clock')}<span>${esc(t('Tapahtumat','Activity'))}</span></button><button data-action="together-demo">${icon('heart')}<span>${esc(t('Yhdessä','Together'))}</span></button></nav></div>`;
}
function reviewView(s,v) {
  return `${taskOpening(s,v,true)}<p class="task-description">${esc(s.note)}</p>${checklist(s,true)}
    ${s.revisionCount?`<p class="notice space">${esc(t('Puhemuutos hyväksytty luonnokseen. Voit vielä muokata ennen tallennusta.','Voice revision accepted into the draft. You can still edit before saving.'))}</p>`:''}</div></div>${toast(s)}
    <footer class="footer stack">${button(t('Muuta puhumalla','Revise by voice'),'revise','tonal fill','mic')}
    ${button(t('Lisää tehtävä','Add task'),'save-draft','fill','check')}<p class="review-help">${esc(t('Lisätään perheen listalle vasta tästä.','Added to your family list only when you confirm.'))}</p></footer>`;
}
function finished(s) {
  const title = s.saved?t('Tehtävä lisätty','Task added'):s.deleted?t('Tehtävä poistettu','Task deleted'):s.cancelled?t('Tehtävä peruttu','Task cancelled'):t('Tehtävä valmis','Task complete');
  return `${bar(t('Tehtävä','Task'),'reset',null)}<div class="content empty">${icon('check')}<h2>${esc(title)}</h2><p>${esc(s.title)}</p>${button(s.saved?t('Katso tehtävää','View task'):t('Kumoa','Undo'),s.saved?'view-saved':'undo','secondary')}</div>`;
}
const field = (label,key,value,multiline=false) => `<label class="field"><span>${esc(label)}</span>${multiline?`<textarea data-field="${key}">${esc(value)}</textarea>`:`<input type="text" data-field="${key}" value="${esc(value)}">`}</label>`;
function overlay(s,v) {
  if (!s.menu) return '';
  let title='', html='', back='menu', showBack=true;
  switch(s.menu) {
    case 'queue-menu':
      title=t('Tehtävälista','Task list'); showBack=false;
      html=`<p class="muted">${esc(t('Tässä tutkitaan tehtävien kuvia ja avaamista. Suodatus ja järjestys säilyvät erillisinä listatoimintoina.','This study explores task artwork and opening details. Filtering and ordering remain separate queue controls.'))}</p>${row(t('Palauta esimerkit','Reset samples'),'reset','undo')}`;
      break;
    case 'adventure-demo':
    case 'new-demo':
    case 'capture-demo':
    case 'activity-demo':
    case 'together-demo':
      title=t('Esittelyn rajaus','Prototype scope'); showBack=false;
      html=`<p>${esc(t('Tässä kokeillaan tehtävälistan ulkoasua, tehtävän tietoja ja tekoälyluonnoksen tarkentamista. Muu sovellus ei muutu tämän luonnoksen mukana.','This study covers the task list, task details and revising an AI draft. The rest of the app is outside this study.'))}</p>${button(t('Kokeile tekoälyluonnosta','Try the AI draft'),'demo-review','fill')}`;
      break;
    case 'menu':
      title=t('Tehtävän toiminnot','Task actions'); showBack=false;
      html=row(t('Ajoitus','Schedule'),'schedule','clock',t('Määräpäivä, lykkäys ja toisto','Due date, snooze and repeat'))+
        row(t('Vaiheet ja tekoäly','Steps and AI'),'steps-menu','list')+
        row(t('Muut toiminnot','More actions'),'more','more',t('Järjestys, peruminen ja poisto','Position, cancellation and deletion'))+
        row(t('Alkuperäinen teksti ja tiedot','Original text and details'),'info','info');
      break;
    case 'review-menu':
      title=t('Luonnos','Draft'); showBack=false;
      html=row(t('Hylkää luonnos','Discard draft'),'discard','trash','',true)+row(t('Alkuperäinen sanelu','Original dictation'),'original','info');
      break;
    case 'edit':
      title=t('Muokkaa tehtävää','Edit task'); showBack=false;
      html=`<div class="fields">${field(t('Tehtävän nimi','Task title'),'editTitle',s.editTitle)}${field(t('Kuvaus','Notes'),'editNote',s.editNote,true)}
        ${s.editError?`<p class="notice error" role="alert">${esc(s.editError)}</p>`:''}${s.items.length?field(t('Vaiheet, yksi riville','Steps, one per line'),'editItems',s.editItems,true):''}</div><div class="stack space">${button(t('Tallenna muutokset','Save changes'),'save-edit','fill')}${button(t('Peruuta','Cancel'),'close','secondary fill')}</div>`;
      break;
    case 'schedule':
      title=t('Ajoitus','Schedule');
      html=row(t('Määräpäivä','Due date'),'date','clock',s.due||t('Ei asetettu','Not set'))+
        row(t('Toisto','Repeat'),'repeat','undo',s.repeat||t('Ei toistu','Does not repeat'))+
        `<h3>${esc(t('Lykkää tehtävää','Snooze task'))}</h3><p class="muted">${esc(t('Piilottaa tehtävän listalta valittuun aikaan asti. Ei muuta määräpäivää.','Hides this task until the chosen time. Does not change its due date.'))}</p>`+
        row(t('Tunniksi','For an hour'),'snooze:hour','clock')+row(t('Huomisaamuun','Until tomorrow morning'),'snooze:tomorrow','clock')+
        row(t('Valitse aika','Choose a time'),'snooze-date','clock')+(s.snooze?row(t('Poista lykkäys','Clear snooze'),'unsnooze','undo',s.snooze):'');
      break;
    case 'date':
    case 'snooze-date':
      title=s.menu==='date'?t('Määräpäivä','Due date'):t('Lykkäyksen päättyminen','Snooze until'); back='schedule';
      html=`<label class="field"><span>${esc(t('Päivä','Date'))}</span><input type="date" data-field="dateChoice" value="${esc(s.dateChoice||'2026-09-18')}"></label>
        <div class="stack space">${button(t('Käytä päivää','Use date'),s.menu==='date'?'apply-date':'apply-snooze','fill')}${s.menu==='date'?button(t('Poista määräpäivä','Clear due date'),'clear-date','secondary fill'):''}</div>`;
      break;
    case 'repeat':
      title=t('Toista tehtävä','Repeat task'); back='schedule';
      html=row(t('Ei toistoa','Does not repeat'),'repeat:none','undo')+row(t('Päivittäin','Daily'),'repeat:daily','undo')+row(t('Viikoittain','Weekly'),'repeat:weekly','undo');
      break;
    case 'steps-menu':
      title=t('Vaiheet ja tekoäly','Steps and AI');
      html=row(t('Lisää vaiheet itse','Add steps myself'),'manual-steps','list')+
        row(t('Ehdota vaiheita tekoälyllä','Suggest steps with AI'),'ai-steps','list')+
        row(t('Siisti teksti tekoälyllä','Clean up text with AI'),'cleanup','edit');
      break;
    case 'manual-steps':
      title=t('Lisää vaiheet','Add steps'); back='steps-menu';
      html=field(t('Yksi vaihe riville','One step per line'),'newSteps',s.newSteps||'',true)+
        `<div class="space">${button(t('Lisää vaiheet','Add steps'),'apply-steps','fill')}</div>`;
      break;
    case 'ai-steps':
    case 'cleanup':
      title=s.menu==='ai-steps'?t('Ehdotetut vaiheet','Suggested steps'):t('Ehdotettu teksti','Suggested text'); back='steps-menu';
      html=`<p class="muted">${esc(t('Tässä esimerkkiehdotus. Oikeassa sovelluksessa teksti lähetettäisiin tekoälylle.','This is a sample suggestion. In the real app, task text would be sent to AI.'))}</p>`+
        (s.menu==='ai-steps'?sampleSteps().map(x=>`<div class="draft-step">${icon('list')}<span>${esc(x)}</span></div>`).join(''):`<p>${esc(t('Etsi isoja mikrokuituliinoja Napolista','Find large microfibre cloths in Naples'))}</p>`)+
        `<div class="stack space">${button(t('Käytä ehdotusta','Use suggestion'),s.menu==='ai-steps'?'apply-ai-steps':'apply-cleanup','fill')}${button(t('Säilytä nykyinen','Keep current'),'close','secondary fill')}</div>`;
      break;
    case 'info':
      title=t('Tehtävän tiedot','Task details');
      html=`<p>${esc(t('Luonut Pasi · 17.9.2026 klo 16.07','Created by Pasi · 17 Sep 2026, 16:07'))}</p><p>${esc(t('Muuttanut Pasi · 17.9.2026 klo 16.08','Changed by Pasi · 17 Sep 2026, 16:08'))}</p>
        ${row(t('Alkuperäinen sanelu','Original dictation'),'original','mic')}${row(t('Aikavyöhyke','Time zone'),'zone','clock')}`;
      break;
    case 'zone':
      title=t('Aikavyöhyke','Time zone'); back='info'; html='<p>Europe/Helsinki</p>'; break;
    case 'original':
      title=t('Alkuperäinen sanelu','Original dictation'); back=screen==='review'?'review-menu':'info';
      html=`<p>${esc(sampleNote())}</p><p class="muted">${esc(t('Alkuperäinen säilyy, vaikka tehtävää muokataan.','The original is kept when the task is edited.'))}</p>`; break;
    case 'more':
      title=t('Muut toiminnot','More actions');
      html=row(t('Siirrä listalla','Move in list'),'position','list',s.position)+row(t('Peru tehtävä','Cancel task'),'cancel-confirm','close')+row(t('Poista tehtävä','Delete task'),'delete-confirm','trash','',true); break;
    case 'position':
      title=t('Siirrä listalla','Move in list'); back='more';
      html=row(t('Listan alkuun','To the top'),'position:top','list')+row(t('Yksi aiemmaksi','One earlier'),'position:earlier','list')+row(t('Yksi myöhemmäksi','One later'),'position:later','list');
      break;
    case 'cancel-confirm':
    case 'delete-confirm':
      title=s.menu==='delete-confirm'?t('Poista tehtävä?','Delete task?'):t('Peru tehtävä?','Cancel task?'); back='more';
      html=`<p>${esc(s.menu==='delete-confirm'?t('Poistetaan perheen yhteiseltä listalta. Poiston voi kumota.','Removes it from the shared family list. You can undo this.'):t('Tehtävä jää tekemättä myös perheen yhteisellä listalla.','Marks the task as cancelled for the family too.'))}</p><div class="stack">${button(s.menu==='delete-confirm'?t('Poista tehtävä','Delete task'):t('Peru tehtävä','Cancel task'),s.menu==='delete-confirm'?'delete':'cancel','danger fill')}${button(t('Säilytä tehtävä','Keep task'),'close','secondary fill')}</div>`;
      break;
    case 'revise':
      title=t('Mitä muutetaan?','What should change?'); showBack=false;
      html=`<p>${esc(t('Kerro vain muutos. Nykyinen luonnos säilyy, kunnes hyväksyt uuden ehdotuksen.','Say just the change. Your current draft stays until you accept a new suggestion.'))}</p>
        <p class="notice">${esc(t('Puhetoiminnon esittely. Mikrofonia ei käytetä eikä tekstiä lähetetä.','Voice-flow demo. No microphone is used and no text is sent.'))}</p>
        ${[['size',t('Tarkenna, että liinan pitää olla vähintään 30 × 30 senttiä.','Specify that the cloth must be at least 30 × 30 centimetres.')],
          ['add',t('Lisää kolme vaihetta: tarkista aukioloajat, kysy liinan mitat ja pyydä hintatiedot. Säilytä nykyiset vaiheet.','Add three steps: check opening hours, ask for cloth dimensions, and request prices. Keep the existing steps.')],
          ['remove',t('Poista viimeinen vaihe. Muu saa pysyä ennallaan.','Remove the last step. Keep everything else.')]].map(([key,label])=>`<button class="sample" aria-pressed="${s.sample===key}" data-action="sample:${key}">${icon('mic')}<span>${esc(label)}</span></button>`).join('')}
        <details><summary>${esc(t('Kokeile virhetilannetta','Try a failure state'))}</summary>
          <label class="completion-row"><input type="checkbox" data-flag="offline" ${s.offline?'checked':''}>${esc(t('Ei verkkoyhteyttä','Offline'))}</label>
          <label class="completion-row"><input type="checkbox" data-flag="error" ${s.error?'checked':''}>${esc(t('Ehdotuksen luonti epäonnistuu','Suggestion fails'))}</label></details>
        <div class="stack space">${button(t('Kokeile esimerkkisanelua','Try sample dictation'),'generate-revision','fill','mic')}${button(t('Peruuta','Cancel'),'close','secondary fill')}</div>
        <p class="review-help">${esc(t('Ehdotus toteutukseen: verkkopuhe lähettää äänen Microsoft Azureen. Nykyinen luonnos ja muutosohje lähetetään tekoälylle vain tätä muutosta varten.','Proposed implementation: online speech sends audio to Microsoft Azure. The current draft and instruction go to AI for this revision.'))}</p>`;
      break;
    case 'revision-error':
      title=t('Luonnos on tallessa','Your draft is safe'); back='revise';
      html=`<p class="notice error">${esc(s.offline?t('Muutos tarvitsee verkkoyhteyden. Voit muokata tekstiä itse.','This revision needs a connection. You can edit the text yourself.'):t('Uutta ehdotusta ei saatu. Nykyistä luonnosta ei muutettu.','No new suggestion was returned. Your current draft is unchanged.'))}</p>
        <div class="stack">${button(t('Takaisin luonnokseen','Back to draft'),'close','fill')}${button(t('Muokkaa itse','Edit myself'),'edit','secondary fill')}${button(t('Yritä uudelleen','Try again'),'retry-revision','secondary fill')}</div>`;
      break;
    case 'revision-diff':
      title=t('Tarkista muutos','Review the change'); showBack=false;
      html=`<p>${esc(JSON.stringify(s.pending.items)===JSON.stringify(s.items)&&s.pending.note===s.note?t('Ohje ei muuta tätä luonnosta. Voit säilyttää nykyisen tai sanella uudelleen.','This instruction makes no change to this draft. Keep it or try another dictation.'):t('Vain nämä kohdat muuttuvat.','Only these parts will change.'))}</p>`+
        (s.pending.note!==s.note?`<div class="change"><h3>${esc(t('Kuvaus','Notes'))}</h3><del>${esc(s.note)}</del><ins>${esc(s.pending.note)}</ins></div>`:'')+
        s.pending.items.filter(x=>!s.items.includes(x)).map(x=>`<div class="change"><h3>${esc(t('Lisätään vaihe','Step added'))}</h3><ins>${esc(x)}</ins></div>`).join('')+
        s.items.filter(x=>!s.pending.items.includes(x)).map(x=>`<div class="change"><h3>${esc(t('Poistetaan vaihe','Step removed'))}</h3><del>${esc(x)}</del></div>`).join('')+
        `<p class="review-help">${esc(t('Esimerkkimuutos, ei oikea tekoälyvastaus. Tehtävää ei vielä lisätä.','Sample change, not a real AI response. This does not create the task.'))}</p>
        <div class="stack space">${button(t('Hyväksy muutos','Accept change'),'accept-revision','fill','',JSON.stringify(s.pending.items)===JSON.stringify(s.items)&&s.pending.note===s.note)}${button(t('Säilytä nykyinen','Keep current'),'reject-revision','secondary fill')}${button(t('Sanele uudelleen','Try another dictation'),'revise','tonal fill','mic')}</div>`;
      break;
    case 'leave-review':
    case 'discard':
      title=t('Hylkää luonnos?','Discard draft?'); showBack=false;
      html=`<p>${esc(t('Esikatselusta ei ole vielä luotu tehtävää.','No task has been created from this preview.'))}</p><div class="stack">${button(t('Hylkää luonnos','Discard draft'),'discard-draft','danger fill')}${button(t('Jatka muokkausta','Keep editing'),'close','secondary fill')}</div>`;
      break;
  }
  return `<div class="overlay" data-dismiss><section class="sheet" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="handle"></div>
    <div class="sheet-header">${showBack?ib('back',t('Takaisin','Back'),back):''}<h2>${esc(title)}</h2>${ib('close',t('Sulje','Close'),'close')}</div>${html}</section></div>`;
}
function render(focusDialog=false) {
  document.documentElement.lang=lang;
  document.querySelector('#study-header').innerHTML=`<div class="study-top"><h1>${esc(t('Tehtäväkuva · kolme vaihtoehtoa','Task artwork · three options'))}</h1><span class="prototype-label">${esc(t('Bun Do · suunnittelukokeilu #70','Bun Do · design study #70'))}</span></div>
    <div class="toolbar"><label>${esc(t('Näkymä','Screen'))}<select id="screen">${[['queue',t('Tehtävälista','Task list')],['task',t('Tavallinen tehtävä','Simple task')],['checklist',t('Tehtävä ja vaiheet','Task with steps')],['review',t('Tekoälyn ehdotus','AI suggestion')]].map(([k,n])=>`<option value="${k}" ${screen===k?'selected':''}>${esc(n)}</option>`).join('')}</select></label>
    <label>${esc(t('Kieli','Language'))}<select id="lang"><option value="fi" ${lang==='fi'?'selected':''}>Suomi</option><option value="en" ${lang==='en'?'selected':''}>English</option></select></label>
    <label><input id="large" type="checkbox" ${large?'checked':''}>${esc(t('Iso teksti','Large text'))}</label>
    <label class="desktop-only"><input id="compare" type="checkbox" ${compare?'checked':''}>${esc(t('Vertaa rinnakkain','Compare side by side'))}</label></div>
    <p class="study-note">${esc(screen==='review'?t('Uusi ehdotus: puheella tarkennus nykyiseen luonnokseen, muutosten esikatselu ja erillinen hyväksyntä. Puhe ja muutokset ovat tässä esimerkkejä.','Proposed feature: revise the current draft by voice, inspect the changes, then accept. Speech and changes are simulated here.'):t('Avaa valikko ja kokeile toimintoja. Kaikki muutokset ovat esimerkkejä tässä välilehdessä, eivät oikeita tehtäviä.','Open the menus and try the actions. Changes are examples in this tab, never real tasks.'))}</p>`;
  const labels=variants();
  document.querySelector('#previews').className=compare?'':'focus';
  document.querySelector('#previews').innerHTML=['A','B','C'].filter(v=>compare||v===variant).map(v=>{
    const s=states[v];
    const finishedState=s.done||s.deleted||s.cancelled||s.saved;
    return `<article class="option ${v===variant?'active':''}" data-variant="${v}">
      <div class="option-label"><span class="option-letter">${v}</span><h2>${esc(labels[v].name)}</h2></div><p class="option-note">${esc(labels[v].note)}</p>
      <section class="device ${large?'large':''}" aria-label="${esc(`${v}: ${labels[v].name}`)}"><div class="app-body" ${s.menu?'inert':''}>
      ${finishedState?finished(s):screen==='review'&&!s.reviewDone?reviewView(s,v):s.view==='queue'?queueBase(s,false,v):focusView(s,v)}</div>${overlay(s,v)}</section>
      <details class="state"><summary>${esc(t('Esittelyn tila','Prototype state'))}</summary><pre>${esc(JSON.stringify(s,null,2))}</pre></details></article>`;
  }).join('');
  document.querySelector('#findings').innerHTML=`<h2>${esc(t('Mitä muuttuisi?','What would change?'))}</h2><p>${esc(t('Valitun A:n toiminnot säilyvät kaikissa: muokkaus ja valmistuminen alhaalla, muut toiminnot valikossa. Tässä A, B ja C vertaavat vain kuvan sijoittelua. Listalla ei ole tehtäväkuvia.','All three keep the selected read-first design: edit and complete below, other actions in the menu. A, B and C now compare artwork placement only. The queue has no task images.'))}</p>
    <details class="space"><summary>${esc(t('Nykyinen toiminta ja uudet ehdotukset','Existing behavior and new proposals'))}</summary><ul>
    <li>${esc(t('Jo olemassa: tekoäly voi luoda enintään 16 suoraa vaihetta ensimmäisestä sanelusta, jos siinä luetellaan tuotteita tai vaiheita. Ei automaattisia keksittyjä vaiheita yhdelle toiminnolle.','Already exists: AI can create up to 16 direct steps from initial dictation when products or steps are explicitly listed. It does not invent steps for a simple action.'))}</li>
    <li>${esc(t('Vaatii perheen tehtävälistan, verkkoyhteyden ja tekoälyanalyysin asetuksen. Paikallisen inboxin sanelu ei luo vaiheita.','Requires a shared family list, connectivity and AI analysis enabled. Local inbox dictation does not create checklist steps.'))}</li>
    <li>${esc(t('Yksi puhemuutos voi lisätä useita vaiheita. Kokeile tekoälyn ehdotusta ja kolmen vaiheen esimerkkisanelua. Tarkistat kaikki muutokset ennen hyväksyntää; nykyinen luonnos säilyy hylättäessä.','One voice revision can add multiple steps. Open AI suggestion and try the three-step sample. Review all changes before accepting; rejection preserves the current draft.'))}</li>
    <li>${esc(t('Tehtävän ylätunniste käyttää tässä samaa olemassa olevaa seikkailumaisemaa. Näin eri tehtävät voivat käyttää samaa kuvaa. Tuotannossa kuvan voi valita tallennetusta seikkailukuvastosta; prototyyppi ei lue sitä palvelimelta.','The task header reuses the same existing adventure scene here. Different tasks can share artwork. Production can select from the stored adventure catalog; this prototype does not fetch that catalog.'))}</li>
    <li>${esc(t('Tämä on selainprototyyppi. Ei mikrofoni-, tekoäly- tai tilikutsuja. Ulkoasu ja suuret fontit on vielä tarkistettava natiivissa Androidissa valinnan jälkeen.','This is a browser prototype. No microphone, AI or account calls. Native Android layout and large-text verification follow after selection.'))}</li></ul></details>`;
  document.querySelector('#switcher').hidden=false;
  document.querySelector('#switcher').innerHTML=`<button data-cycle="-1" aria-label="${esc(t('Edellinen vaihtoehto','Previous option'))}">${icon('back')}</button><span class="variant-name">${variant} · ${esc(labels[variant].name)}</span><button data-cycle="1" aria-label="${esc(t('Seuraava vaihtoehto','Next option'))}">${icon('next')}</button>`;
  updateUrl();
  if(focusDialog) requestAnimationFrame(()=>document.querySelector(`.option[data-variant="${variant}"] .sheet button`)?.focus({preventScroll:true}));
}
function updateUrl() {
  const q=new URLSearchParams({variant,screen,lang,mode:compare?'compare':'focus'});
  if(large)q.set('text','large'); history.replaceState(null,'',`?${q}`);
}
function snapshot(s) { return {title:s.title,note:s.note,items:[...s.items],checked:[...s.checked],done:s.done,deleted:s.deleted,cancelled:s.cancelled,claimed:s.claimed,due:s.due,snooze:s.snooze,repeat:s.repeat,position:s.position}; }
function notice(s,message) { s.menu=null; s.toast=message; }
function change(s,fn,message) { s.undo=snapshot(s); fn(); notice(s,message); }
function action(v,a) {
  variant=v; const s=states[v]; const [kind,value]=a.split(':');
  if(kind==='reset') { states[v]=initial();taskMemory[v]={};render();return; }
  if(kind==='close') { s.menu=null; s.pending=null; }
  else if(kind==='queue'||kind==='close-sheet') { s.view='queue';s.menu=null; }
  else if(kind==='open-task') {
    const chosen=taskSamples().find(x=>x.key===value);
    if(chosen&&chosen.key!==s.taskKey){
      taskMemory[v][s.taskKey]=structuredClone(s);
      Object.assign(s,initial(),taskMemory[v][chosen.key]||{taskKey:chosen.key,title:chosen.title,note:chosen.note,items:[]});
    }
    s.view='detail';
  }
  else if(kind==='claim') change(s,()=>s.claimed=!s.claimed,s.claimed?t('Varaus vapautettu','Claim released'):t('Tehtävä varattu sinulle','Task claimed by you'));
  else if(kind==='complete') change(s,()=>s.done=true,t('Tehtävä valmis','Task complete'));
  else if(kind==='undo') { if(s.undo)Object.assign(s,s.undo); s.undo=null; s.toast=''; s.done=false; s.deleted=false; s.cancelled=false; }
  else if(kind==='discard-draft') {s.reviewDone=true;s.view='queue';s.menu=null;s.pending=null;notice(s,t('Luonnos hylätty. Esimerkkilista ei muuttunut.','Draft discarded. The sample list is unchanged.'));Object.assign(s,{title:sampleTitle(),note:sampleNote(),items:[],checked:[]});}
  else if(kind==='save-inline') { if(!s.title.trim()){notice(s,t('Anna tehtävälle nimi','Add a task title'));render();return;} s.dirty=false; notice(s,t('Muutokset tallennettu esittelyyn','Changes saved in the demo')); }
  else if(kind==='edit') { s.editError='';s.editTitle=s.title; s.editNote=s.note; s.editItems=s.items.join('\n'); s.menu='edit'; }
  else if(kind==='save-edit') {
    if(!s.editTitle.trim()) {s.editError=t('Anna tehtävälle nimi','Add a task title');render(true);return;}
    change(s,()=>{
      const completed=new Set(s.checked.map(i=>s.items[i]));
      s.title=s.editTitle.trim();s.note=s.editNote;
      s.items=s.editItems.split('\n').map(x=>x.trim()).filter(Boolean);
      s.checked=s.items.flatMap((item,i)=>completed.has(item)?[i]:[]);
      s.done=s.items.length>0&&s.checked.length===s.items.length;
    },t('Muutokset tallennettu','Changes saved'));
  }
  else if(kind==='snooze') change(s,()=>s.snooze=value==='hour'?t('Tunnin ajan','For an hour'):t('Huomisaamuun','Until tomorrow morning'),t('Lykkäys asetettu','Snooze set'));
  else if(kind==='unsnooze') change(s,()=>s.snooze='',t('Lykkäys poistettu','Snooze cleared'));
  else if(kind==='apply-date') change(s,()=>s.due=s.dateChoice||'2026-09-18',t('Määräpäivä asetettu','Due date set'));
  else if(kind==='clear-date') change(s,()=>s.due='',t('Määräpäivä poistettu','Due date cleared'));
  else if(kind==='apply-snooze') change(s,()=>s.snooze=s.dateChoice||'2026-09-18',t('Lykkäys asetettu','Snooze set'));
  else if(kind==='repeat'&&value) change(s,()=>s.repeat=value==='none'?'':value==='daily'?t('Päivittäin','Daily'):t('Viikoittain','Weekly'),t('Toisto päivitetty','Repeat updated'));
  else if(kind==='position'&&value) change(s,()=>s.position=value,t('Sijainti päivitetty esimerkkilistalla','Position updated in sample list'));
  else if(kind==='apply-steps') { const items=(s.newSteps||'').split('\n').map(x=>x.trim()).filter(Boolean); if(items.length)change(s,()=>s.items.push(...items),t('Vaiheet lisätty','Steps added')); }
  else if(kind==='apply-ai-steps') change(s,()=>{s.items=sampleSteps();s.checked=[];},t('Esimerkkivaiheet lisätty','Sample steps added'));
  else if(kind==='apply-cleanup') change(s,()=>s.title=t('Etsi isoja mikrokuituliinoja Napolista','Find large microfibre cloths in Naples'),t('Esimerkkiteksti hyväksytty','Sample text accepted'));
  else if(kind==='cancel'||kind==='delete') change(s,()=>s[kind==='delete'?'deleted':'cancelled']=true,'');
  else if(kind==='sample') s.sample=value;
  else if(kind==='generate-revision'||kind==='retry-revision') {
    if(s.error||s.offline)s.menu='revision-error';
    else {
      s.pending={title:s.title,note:s.note,items:[...s.items]};
      const size=t('Liinan pitää olla vähintään 30 × 30 cm.','The cloth must be at least 30 × 30 cm.');
      if(s.sample==='size'&&!s.note.includes(size))s.pending.note=s.note+'\n'+size;
      const added=[t('Tarkista liikkeen aukioloajat','Check the shop’s opening hours'),t('Kysy liinan mitat','Ask for cloth dimensions'),t('Pyydä hintatiedot','Request prices')];
      if(s.sample==='add')s.pending.items.push(...added.filter(item=>!s.pending.items.includes(item)));
      if(s.sample==='remove')s.pending.items.pop();
      s.menu='revision-diff';
    }
  }
  else if(kind==='accept-revision') {s.history.push(snapshot(s));Object.assign(s,s.pending);s.pending=null;s.revisionCount++;notice(s,t('Muutos hyväksytty luonnokseen','Revision accepted into draft'));}
  else if(kind==='reject-revision') {s.pending=null;notice(s,t('Nykyinen luonnos säilytettiin','Kept the current draft'));}
  else if(kind==='save-draft') {s.saved=true;s.menu=null;}
  else if(kind==='view-saved') {s.reviewDone=true;s.view='detail';s.saved=false;}
  else if(kind==='demo-review') {screen='review';states={A:initial(),B:initial(),C:initial()};render();return;}
  else s.menu=kind;
  // Only one preview opens a modal at a time.
  for(const key of ['A','B','C'])if(key!==v)states[key].menu=null;
  render(!!s.menu);
  console.info('Prototype state',v,structuredClone(s));
}
document.addEventListener('click',event=>{
  const cycle=event.target.closest('[data-cycle]');
  if(cycle) {
    variant=['A','B','C'][(['A','B','C'].indexOf(variant)+Number(cycle.dataset.cycle)+3)%3];
    for(const s of Object.values(states))s.menu=null;
    render();console.info('Variant',variant,structuredClone(states[variant]));return;
  }
  const target=event.target.closest('[data-action]');
  if(target)action(target.closest('[data-variant]').dataset.variant,target.dataset.action);
  else if(event.target.matches('[data-dismiss]'))action(event.target.closest('[data-variant]').dataset.variant,'close');
});
document.addEventListener('input',event=>{
  const input=event.target, v=input.closest('[data-variant]')?.dataset.variant;
  if(!v||!input.dataset.field)return;
  states[v][input.dataset.field]=input.value;
  if(['title','note'].includes(input.dataset.field)){
    states[v].dirty=true;
    const save=document.querySelector(`[data-variant="${v}"] [data-action="save-inline"]`);
    if(save){save.disabled=false;save.innerHTML=icon('check')+esc(t('Tallenna muutokset','Save changes'));}
  }
});
document.addEventListener('change',event=>{
  const el=event.target;
  if(el.id==='screen'||el.id==='lang') {if(el.id==='screen')screen=el.value;else lang=el.value;states={A:initial(),B:initial(),C:initial()};taskMemory={A:{},B:{},C:{}};render();}
  else if(el.id==='large'){large=el.checked;render();}
  else if(el.id==='compare'){compare=el.checked;render();}
  else {
    const v=el.closest('[data-variant]')?.dataset.variant;if(!v)return;
    const s=states[v];
    if(el.hasAttribute('data-complete'))action(v,'complete');
    else if(el.hasAttribute('data-step')) {
      const i=Number(el.dataset.step);s.undo=snapshot(s);
      s.checked=el.checked?[...s.checked,i]:s.checked.filter(x=>x!==i);
      s.done=s.checked.length===s.items.length;render();
    }
    else if(el.dataset.flag)s[el.dataset.flag]=el.checked;
  }
});
document.addEventListener('keydown',event=>{
  const modal=document.querySelector('.sheet');
  if(modal&&event.key==='Escape'){event.preventDefault();action(modal.closest('[data-variant]').dataset.variant,'close');return;}
  if(modal&&event.key==='Tab'){
    const nodes=[...modal.querySelectorAll('button:not(:disabled),input,textarea,summary,select')].filter(x=>x.getClientRects().length);
    const first=nodes[0],last=nodes.at(-1);
    if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
  }
  if(!modal&&!event.target.closest('input,textarea,select,[contenteditable]')&&['ArrowLeft','ArrowRight'].includes(event.key)){
    event.preventDefault();document.querySelector(`[data-cycle="${event.key==='ArrowLeft'?-1:1}"]`).click();
  }
});
render();
