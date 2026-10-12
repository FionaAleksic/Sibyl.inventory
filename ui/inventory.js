'use strict';
const $=id=>document.getElementById(id),node=(tag,content)=>{const e=document.createElement(tag);e.textContent=String(content??'');return e;};
let devices=[],peripherals=[],networks=[],editingDevice=null,editingPeripheral=null;
function status(message){$('status').textContent=message;}
function validIp(text){
 const p=String(text).split('.');
 return p.length===4&&p.every(x=>/^(0|[1-9][0-9]{0,2})$/.test(x)&&+x<=255);
}
function intIp(value){let n=0;for(const c of value.split('.'))n=((n<<8)|Number(c))>>>0;return n;}
function strIp(n){return [n>>>24,(n>>>16)&255,(n>>>8)&255,n&255].join('.');}
function cell(tr,value){tr.append(node('td',value));}
function action(tr,onEdit,onArchive){
 const td=node('td',''),edit=node('button','Bearbeiten'),del=node('button','Archivieren');
 edit.onclick=onEdit;del.onclick=onArchive;td.append(edit,del);tr.append(td);
}
function tableRows(bucket,rows,columns,body){
 $(body).replaceChildren();
 for(const item of rows){const tr=node('tr','');
  for(const name of columns)cell(tr,item.value[name]||'—');
  if(bucket==='devices'){cell(tr,'Nicht gemessen');}
  action(tr,()=>showForm(bucket,item),async()=>{
   if(!confirm('Eintrag archivieren?'))return;
   try{await SibylBridge.archive(bucket,item.id,item.version);await reload(bucket);}
   catch(e){status(e.message);}
  });$(body).append(tr);
 }
}
function render(){
 const filter=$('device-search').value.toLocaleLowerCase('de');
 tableRows('devices',devices.filter(d=>Object.values(d.value).join(' ').toLocaleLowerCase('de').includes(filter)),
  ['name','type','ip','mac','status'],'device-rows');
 tableRows('peripherals',peripherals,['name','type','vendor','serial','parent','status'],'peripheral-rows');
 $('stats').textContent=devices.length+' Geräte · '+peripherals.length+' Peripheriegeräte';
}
function showForm(bucket,item){
 const kind=bucket==='devices'?'device':'peripheral';
 const fields=kind==='device'?['name','type','ip','mac','status','location']:['name','type','vendor','serial','parent','status'];
 if(kind==='device')editingDevice=item;else editingPeripheral=item;
 for(const name of fields)$(kind+'-'+name).value=item?.value[name]||'';
 if(!item){$(kind+'-type').selectedIndex=0;$(kind+'-status').selectedIndex=0;}
 $(kind+'-form').hidden=false;$(kind+'-name').focus();
}
async function reload(bucket){
 const result=await SibylBridge.list(bucket);
 if(bucket==='devices')devices=result;else if(bucket==='peripherals')peripherals=result;else networks=result;
 render();
}
async function submit(bucket,event){
 event.preventDefault();
 const kind=bucket==='devices'?'device':'peripheral';
 const fields=kind==='device'?['name','type','ip','mac','status','location']:['name','type','vendor','serial','parent','status'];
 const value={};for(const key of fields)value[key]=$(kind+'-'+key).value.trim();
 if(!value.name){status('Name erforderlich.');return;}
 if(kind==='device'&&value.ip&&!validIp(value.ip)){status('Ungültige IPv4-Adresse.');return;}
 const item=kind==='device'?editingDevice:editingPeripheral;
 try{
  if(item)await SibylBridge.update(bucket,item.id,item.version,value);
  else await SibylBridge.create(bucket,value);
  $(kind+'-form').hidden=true;await reload(bucket);status('Gespeichert.');
 }catch(e){status('Speichern fehlgeschlagen: '+e.message);}
}
$('device-form').onsubmit=e=>submit('devices',e);
$('peripheral-form').onsubmit=e=>submit('peripherals',e);
$('new-device').onclick=()=>showForm('devices',null);$('new-peripheral').onclick=()=>showForm('peripherals',null);
$('device-cancel').onclick=()=>$('device-form').hidden=true;
$('peripheral-cancel').onclick=()=>$('peripheral-form').hidden=true;
$('device-search').oninput=render;
document.querySelectorAll('[data-tab]').forEach(b=>{
 b.onclick=()=>{document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('active',x===b));
  ['devices','peripherals','ipam'].forEach(id=>$(id).hidden=id!==b.dataset.tab);};
});
function selectedNetwork(){return networks.find(n=>n.value.cidr===$('cidr').value.trim());}
function showIpam(){
 const cidr=$('cidr').value.trim().split('/');
 if(cidr.length!==2||!validIp(cidr[0])||!/^(1[6-9]|2[0-9]|30)$/.test(cidr[1])){status('Gültiges Netzwerk /16 bis /30 eingeben.');return;}
 const prefix=Number(cidr[1]),total=2**(32-prefix),first=intIp(cidr[0]);
 if(first%total!==0){status('Bitte Netzwerkadresse angeben, keine Hostadresse.');return;}
 let leases,reserved;
 try{leases=JSON.parse($('leases').value);if(!leases||Array.isArray(leases)||typeof leases!=='object')throw Error('Leases müssen ein JSON-Objekt sein.');
 reserved=new Set($('reservations').value.split(/[,;\n]/).map(x=>x.trim()).filter(Boolean));
 for(const ip of [...Object.keys(leases),...reserved])if(!validIp(ip)||((intIp(ip)-first)>>>0)<1||((intIp(ip)-first)>>>0)>=total-1)throw Error('IP außerhalb des Netzes: '+ip);
 }catch(e){status('IPAM-Daten ungültig: '+e.message);return;}
 const search=$('ip-search').value.trim(),page=Math.max(1,Number($('ip-page').value)||1);
 const matches=[];
 for(let n=1;n<total-1;n++){
  const ip=strIp(first+n);
  if(search&&!ip.includes(search))continue;
  matches.push(ip);
 }
 const perPage=200,start=(page-1)*perPage;
 $('ip-rows').replaceChildren();
 for(const ip of matches.slice(start,start+perPage)){
  const tr=node('tr',''),device=devices.find(d=>d.value.ip===ip);
  const lease=Object.hasOwn(leases,ip)?String(leases[ip]):'';
  cell(tr,ip);cell(tr,device||lease?'Belegt':reserved.has(ip)?'Reserviert':'Frei');
  cell(tr,device?.value.name||lease||'—');cell(tr,'Unbekannt (kein Collector)');
  $('ip-rows').append(tr);
 }
 $('ip-summary').textContent=matches.length+' nutzbare Adressen (vollständig erfasst), Seite '+page+
  ' von '+Math.max(1,Math.ceil(matches.length/perPage))+'. Kein Live-Netzwerkscan.';
 status('IPAM-Ansicht aktualisiert.');
}
$('ipam-form').onsubmit=async e=>{
 e.preventDefault();const value={cidr:$('cidr').value.trim(),leases:$('leases').value,reservations:$('reservations').value};
 try{showIpam();const exists=selectedNetwork();
  if(exists)await SibylBridge.update('networks',exists.id,exists.version,value);
  else await SibylBridge.create('networks',value);
  await reload('networks');status('Subnetzkonfiguration gespeichert.');
 }catch(e){status('IPAM speichern fehlgeschlagen: '+e.message);}
};
$('ip-search').oninput=showIpam;$('ip-page').onchange=showIpam;
Promise.all([SibylBridge.list('devices'),SibylBridge.list('peripherals'),SibylBridge.list('networks')])
 .then(([d,p,n])=>{
  devices=d;peripherals=p;networks=n;render();
  if(networks.length){
   const c=networks[0].value;$('cidr').value=c.cidr;$('leases').value=c.leases;
   $('reservations').value=c.reservations;
  }
  showIpam();
 }).catch(e=>status('Datenspeicher nicht verfügbar: '+e.message));