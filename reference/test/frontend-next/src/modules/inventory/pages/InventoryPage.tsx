import {useEffect,useMemo,useState,type FormEvent} from 'react';
import {inventoryService} from '../services/inventoryService';
import {errorMessage} from '../../../shared/services/apiClient';
import type {InventoryItem,InventoryComponent,InventorySubnet,InventoryLocation,IpAddress} from '../../../shared/types';

type Panel='items'|'components'|'ip';
type Sort={key:string|null;direction:'asc'|'desc'};
type AnyRow=Record<string,any>;

const itemStatusLabel=(value:unknown)=>({
  ACTIVE:'Aktiv',STORAGE:'Lager',REPAIR:'Reparatur',RETIRED:'Ausgemustert',DISPOSED:'Entsorgt'
}[String(value??'').toUpperCase()]||String(value||'—'));
const connectivityLabel=(value:unknown)=>({
  ONLINE:'Online',OFFLINE:'Offline',UNKNOWN:'—'
}[String(value??'').toUpperCase()]||'—');
const connectivityClass=(value:unknown)=>{
  const v=String(value??'').toUpperCase();
  return v==='ONLINE'?'online':v==='OFFLINE'?'offline':'unknown';
};
const typeLabel=(value:unknown)=>({
  COMPUTER:'Computer',LAPTOP:'Laptop',SERVER:'Server',MONITOR:'Monitor',MOUSE:'Maus',KEYBOARD:'Tastatur',
  USB_STORAGE:'USB-Datenträger',USB_DEVICE:'USB-Gerät',DOCK:'Dockingstation',AUDIO:'Audio',CAMERA:'Kamera',
  PRINTER:'Drucker',BLUETOOTH:'Bluetooth-Gerät',HID:'HID-Gerät',NETWORK:'Netzwerkgerät',
  PERIPHERAL:'Peripherie',CPU:'CPU',RAM:'RAM',DISK:'Datenträger',GPU:'GPU',OTHER:'Sonstiges'
}[String(value??'').toUpperCase()]||String(value||'—'));
const componentStatusLabel=(value:unknown)=>({
  INSTALLED:'Eingebaut',ARCHIVED:'Archiv',CHECK:'Prüfen',DEFECTIVE:'Defekt',DISPOSED:'Entsorgt'
}[String(value??'').toUpperCase()]||String(value||'—'));
const ipStatusLabel=(value:unknown)=>({
  OCCUPIED:'Belegt',FREE:'Frei',RESERVED:'Reserviert',CONFLICT:'Konflikt',GATEWAY:'Gateway',
  NETWORK:'Netzwerkadresse',BROADCAST:'Broadcast'
}[String(value??'').toUpperCase()]||String(value||'—'));
const show=(value:unknown)=>value==null||value===''?'—':String(value);
const formatDate=(value:unknown)=>{
  if(!value)return '—';
  const d=new Date(String(value));
  if(Number.isNaN(d.getTime()))return String(value);
  return new Intl.DateTimeFormat('de-DE',{dateStyle:'short',timeStyle:'medium'}).format(d);
};
const capacityLabel=(value:unknown)=>{
  const bytes=Number(value);
  if(!Number.isFinite(bytes)||bytes<=0)return '—';
  const gb=bytes/1024/1024/1024;
  return gb>=1?gb.toFixed(gb>=100?0:1)+' GB':String(bytes)+' B';
};
const ipv4=(value:string)=>{
  const parts=value.split('.').map(Number);
  return parts.length===4&&parts.every(x=>Number.isInteger(x)&&x>=0&&x<=255)
    ? (((parts[0]*256+parts[1])*256+parts[2])*256+parts[3]) : null;
};
const inPool=(ip:string,subnet:InventorySubnet|null)=>{
  if(!subnet?.dhcp_start||!subnet?.dhcp_end)return false;
  const x=ipv4(ip),a=ipv4(subnet.dhcp_start),b=ipv4(subnet.dhcp_end);
  return x!=null&&a!=null&&b!=null&&x>=a&&x<=b;
};
const compare=(a:unknown,b:unknown,direction:'asc'|'desc')=>{
  const factor=direction==='asc'?1:-1;
  if(a==null&&b==null)return 0;if(a==null)return 1;if(b==null)return -1;
  if(typeof a==='number'||typeof b==='number')return ((Number(a)||0)-(Number(b)||0))*factor;
  return String(a).localeCompare(String(b),'de',{numeric:true,sensitivity:'base'})*factor;
};
const sorted=<T,>(rows:T[],sort:Sort,resolver:(row:T,key:string)=>unknown)=>{
  if(!sort.key)return rows;
  return [...rows].sort((a,b)=>compare(resolver(a,sort.key!),resolver(b,sort.key!),sort.direction));
};

function SortHeader({label,keyName,sort,onSort}:{label:string;keyName:string;sort:Sort;onSort:(key:string)=>void}){
  const active=sort.key===keyName;
  return <th className="inventory-sortable-header" aria-sort={active?(sort.direction==='asc'?'ascending':'descending'):'none'}>
    <button type="button" className="inventory-sort-button" onClick={()=>onSort(keyName)}>
      <span>{label}</span><span className={'inventory-sort-indicator'+(active?' active':'')} aria-hidden="true">
        {active?(sort.direction==='asc'?'▲':'▼'):'↕'}
      </span>
    </button>
  </th>;
}
function Metric({label,value}:{label:string;value:unknown}){
  return <div className="inventory-metric"><span>{label}</span><strong>{show(value??0)}</strong></div>;
}
function DetailField({label,value,className=''}:{label:string;value:unknown;className?:string}){
  return <div className={'inventory-detail-field'+(className?' '+className:'')}>
    <small>{label}</small><strong>{show(value)}</strong>
  </div>;
}
function MiniRow({title,meta,right}:{title:string;meta?:string;right?:string}){
  return <div className="inventory-mini-row"><div><strong>{title}</strong>{meta&&<><br/><small>{meta}</small></>}</div><div>{right||'—'}</div></div>;
}

export function InventoryPage({isAdmin=false}:{isAdmin?:boolean}){
  const [panel,setPanel]=useState<Panel>('items');
  const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  const [summary,setSummary]=useState<AnyRow>({});
  const [items,setItems]=useState<InventoryItem[]>([]);const [selectedId,setSelectedId]=useState<string|null>(null);
  const [selected,setSelected]=useState<InventoryItem|null>(null);const [history,setHistory]=useState<AnyRow[]>([]);
  const [itemSearch,setItemSearch]=useState('');const [itemStatus,setItemStatus]=useState('all');const [itemType,setItemType]=useState('all');
  const [itemSort,setItemSort]=useState<Sort>({key:null,direction:'asc'});
  const [components,setComponents]=useState<InventoryComponent[]>([]);const [componentSummary,setComponentSummary]=useState<AnyRow>({});
  const [componentId,setComponentId]=useState<string|null>(null);const [componentSearch,setComponentSearch]=useState('');
  const [componentStatus,setComponentStatus]=useState('all');const [componentType,setComponentType]=useState('all');
  const [componentSort,setComponentSort]=useState<Sort>({key:null,direction:'asc'});
  const [subnets,setSubnets]=useState<InventorySubnet[]>([]);const [subnetId,setSubnetId]=useState<string|null>(null);
  const [addresses,setAddresses]=useState<IpAddress[]>([]);const [ipSearch,setIpSearch]=useState('');
  const [ipStatus,setIpStatus]=useState('all');const [ipPool,setIpPool]=useState('all');const [ipSource,setIpSource]=useState('all');
  const [ipSort,setIpSort]=useState<Sort>({key:'ip_address',direction:'asc'});
  const [locations,setLocations]=useState<InventoryLocation[]>([]);
  const [editItem,setEditItem]=useState<AnyRow|null>(null);

  const subnet=useMemo(()=>subnets.find(s=>String(s.id)===String(subnetId))??null,[subnets,subnetId]);
  const currentComponent=useMemo(()=>components.find(c=>String(c.id)===String(componentId))??null,[components,componentId]);

  const toggleSort=(setter:React.Dispatch<React.SetStateAction<Sort>>,key:string)=>setter(old=>({
    key,direction:old.key===key&&old.direction==='asc'?'desc':'asc'
  }));

  const visibleItems=useMemo(()=>sorted(items,itemSort,(row,key)=>{
    const x=row as AnyRow;
    if(key==='type')return typeLabel(x.item_type);
    if(key==='connectivity')return connectivityLabel(x.connectivity_status);
    if(key==='status')return itemStatusLabel(x.status);
    if(key==='location')return x.location_name;
    if(key==='last_seen')return x.last_seen?new Date(x.last_seen).getTime():null;
    return x[key];
  }),[items,itemSort]);

  const visibleComponents=useMemo(()=>sorted(components,componentSort,(row,key)=>{
    const x=row as AnyRow;
    if(key==='type')return typeLabel(x.component_type);
    if(key==='capacity')return Number(x.capacity_bytes||0);
    if(key==='status')return componentStatusLabel(x.component_status);
    if(key==='computer')return x.computer_hostname||x.computer_inventory_number||x.computer_name;
    if(key==='last_seen')return x.last_seen?new Date(x.last_seen).getTime():null;
    if(key==='management')return x.manual_status||'AUTO';
    return x[key];
  }),[components,componentSort]);

  const filteredIps=useMemo(()=>{
    const q=ipSearch.trim().toLowerCase();
    const rows=addresses.filter(row=>{
      const status=String(row.status||'').toUpperCase();
      if(ipStatus!=='all'&&status!==ipStatus)return false;
      const pool=inPool(row.ip_address,subnet);
      if(ipPool==='DHCP'&&!pool)return false;
      if(ipPool==='OUTSIDE'&&pool)return false;
      if(ipSource!=='all'){
        const sources=String(row.source||'').toLowerCase().split(',').map(x=>x.trim()).filter(Boolean);
        const ok=sources.some(source=>{
          if(ipSource==='dhcp')return source==='dhcp'||source==='dhcp-reservierung';
          if(ipSource==='zyxel-dhcp')return source==='zyxel-dhcp'||source==='zyxel-dhcp-reservierung';
          return source===ipSource;
        });
        if(!ok)return false;
      }
      if(!q)return true;
      return [row.ip_address,row.status,row.hostname,row.device,row.mac_address,row.source,row.label,row.description]
        .some(v=>String(v||'').toLowerCase().includes(q));
    });
    return sorted(rows,ipSort,(row,key)=>{
      const x=row as AnyRow;
      if(key==='ip_address')return ipv4(String(x.ip_address))??0;
      if(key==='status')return ipStatusLabel(x.status);
      if(key==='last_seen')return x.last_seen?new Date(x.last_seen).getTime():null;
      return x[key];
    });
  },[addresses,ipSearch,ipStatus,ipPool,ipSource,subnet,ipSort]);

  async function loadItemDetail(id:string,silent=false){
    try{
      const [item,h]=await Promise.all([inventoryService.get(id),inventoryService.history(id)]);
      setSelectedId(id);setSelected(item);setHistory(h);
    }catch(e){if(!silent)setError(errorMessage(e));}
  }
  async function loadItems(keep=true,silent=false){
    const [s,rows,locs]=await Promise.all([
      inventoryService.summary(),
      inventoryService.list({q:itemSearch,status:itemStatus,type:itemType}),
      inventoryService.locations()
    ]);
    setSummary(s);setItems(rows);setLocations(locs);
    const wanted=keep&&selectedId&&rows.some(x=>String(x.id)===String(selectedId))
      ?selectedId:(rows[0]?.id??null);
    setSelectedId(wanted);
    if(wanted)await loadItemDetail(wanted,silent);else{setSelected(null);setHistory([]);}
  }
  async function loadComponents(){
    const [s,rows]=await Promise.all([
      inventoryService.componentSummary(),
      inventoryService.components({q:componentSearch,status:componentStatus,type:componentType,limit:2000})
    ]);
    setComponentSummary(s);setComponents(rows);
    const wanted=componentId&&rows.some(x=>String(x.id)===String(componentId))?componentId:(rows[0]?.id??null);
    setComponentId(wanted);
  }
  async function loadIps(keep=true){
    const nets=await inventoryService.subnets();setSubnets(nets);
    const wanted=keep&&subnetId&&nets.some(x=>String(x.id)===String(subnetId))?subnetId:(nets[0]?.id??null);
    setSubnetId(wanted);
    setAddresses(wanted?await inventoryService.addresses(wanted):[]);
  }
  async function refresh(silent=false){
    if(!silent){setBusy(true);setError('');}
    try{
      if(panel==='items')await loadItems(true,silent);
      else if(panel==='components')await loadComponents();
      else await loadIps(true);
    }catch(e){if(!silent)setError(errorMessage(e));}
    finally{if(!silent)setBusy(false);}
  }
  useEffect(()=>{void refresh();},[panel]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{
    const timer=window.setInterval(()=>{void refresh(true);},5000);
    return()=>window.clearInterval(timer);
  },[panel,itemSearch,itemStatus,itemType,componentSearch,componentStatus,componentType,subnetId]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{
    const timer=window.setTimeout(()=>{if(panel==='items')void refresh(true);},220);
    return()=>window.clearTimeout(timer);
  },[itemSearch,itemStatus,itemType]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{
    const timer=window.setTimeout(()=>{if(panel==='components')void refresh(true);},220);
    return()=>window.clearTimeout(timer);
  },[componentSearch,componentStatus,componentType]); // eslint-disable-line react-hooks/exhaustive-deps

  async function selectSubnet(id:string){setSubnetId(id);setIpSearch('');setIpStatus('all');setIpPool('all');setIpSource('all');
    try{setAddresses(await inventoryService.addresses(id));}catch(e){setError(errorMessage(e));}}
  async function updateComponent(c:InventoryComponent,status:string){
    setBusy(true);setError('');try{await inventoryService.componentStatus(c.id,status);setComponentId(c.id);await loadComponents();}
    catch(e){setError(errorMessage(e));}finally{setBusy(false);}
  }
  async function saveItem(e:FormEvent){
    e.preventDefault();if(!editItem)return;setBusy(true);setError('');
    try{
      const id=String(editItem.id||'');
      const body={...editItem};delete body.id;
      const saved=id?await inventoryService.update(id,body):await inventoryService.create(body);
      setEditItem(null);setSelectedId(saved.id);await loadItems(true);
    }catch(err){setError(errorMessage(err));}finally{setBusy(false);}
  }

  const itemAny=selected as AnyRow|null;
  const knownUsers=(itemAny?.known_users as AnyRow[]|undefined)??[];
  const displayUser=itemAny?.display_name||itemAny?.last_user||itemAny?.current_user||'';
  const ipCounts={
    total:addresses.length,
    occupied:addresses.filter(x=>x.status==='OCCUPIED').length,
    free:addresses.filter(x=>x.status==='FREE').length,
    reserved:addresses.filter(x=>x.status==='RESERVED').length,
    conflicts:addresses.filter(x=>x.status==='CONFLICT').length,
    observed:addresses.filter(x=>String(x.source||'').trim()).length
  };

  return <section className="inventory-view" aria-labelledby="inventoryTitle">
    <div className="inventory-header">
      <div><h1 id="inventoryTitle">Inventar</h1>
        <p className="muted compact">Geräte und Gegenstände zentral erfassen, automatisch aktualisieren und lückenlos nachverfolgen.</p>
      </div>
      <div className="inventory-actions">
        <button className="button secondary" type="button" disabled={busy} onClick={()=>void refresh()}>Aktualisieren</button>
        {isAdmin&&panel==='items'&&<button className="button" type="button" onClick={()=>setEditItem({
          name:'',inventory_number:'',item_type:'COMPUTER',status:'ACTIVE',manufacturer:'',model:'',serial_number:'',
          assigned_to:'',location_id:'',hostname:'',smbios_uuid:'',domain:'',purchase_date:'',purchase_price:'',
          warranty_until:'',description:'',notes:''
        })}>+ Gegenstand</button>}
      </div>
    </div>

    {error&&<div className="message error">{error}</div>}

    <div className="inventory-tabs" role="tablist" aria-label="Inventarbereiche">
      <button className={'inventory-tab '+(panel==='items'?'active':'')} type="button" onClick={()=>setPanel('items')}>Gegenstände</button>
      <button className={'inventory-tab '+(panel==='components'?'active':'')} type="button" onClick={()=>setPanel('components')}>Komponenten</button>
      <button className={'inventory-tab '+(panel==='ip'?'active':'')} type="button" onClick={()=>setPanel('ip')}>IP-Adressen</button>
    </div>

    {panel==='items'&&<section className="inventory-subpanel">
      <div className="inventory-metrics" aria-label="Inventarübersicht">
        <Metric label="Gesamt" value={summary.total}/><Metric label="Online" value={summary.online}/>
        <Metric label="Offline" value={summary.offline}/><Metric label="Lager" value={summary.storage}/>
        <Metric label="Reparatur" value={summary.repair}/><Metric label="Ausgemustert" value={summary.retired}/>
      </div>
      <div className="inventory-filters">
        <label className="inventory-search"><span className="field-label">Suchen</span>
          <input type="search" placeholder="Inventarnummer, Name, Hostname, Seriennummer …" autoComplete="off"
            value={itemSearch} onChange={e=>setItemSearch(e.target.value)}/></label>
        <label><span className="field-label">Inventarstatus</span><select value={itemStatus} onChange={e=>setItemStatus(e.target.value)}>
          <option value="all">Alle</option><option value="ACTIVE">Aktiv</option><option value="STORAGE">Lager</option>
          <option value="REPAIR">Reparatur</option><option value="RETIRED">Ausgemustert</option><option value="DISPOSED">Entsorgt</option>
        </select></label>
        <label><span className="field-label">Typ</span><select value={itemType} onChange={e=>setItemType(e.target.value)}>
          <option value="all">Alle</option><option value="COMPUTER">Computer</option><option value="LAPTOP">Laptop</option>
          <option value="SERVER">Server</option><option value="MONITOR">Monitor</option><option value="NETWORK">Netzwerk</option>
          <option value="PERIPHERAL">Peripherie</option><option value="OTHER">Sonstiges</option>
        </select></label>
        <span className="count-badge inventory-count">{items.length}</span>
      </div>
      <div className="inventory-split">
        <section className="inventory-list-panel" aria-labelledby="inventoryListTitle">
          <div className="inventory-panel-head"><h2 id="inventoryListTitle">Gegenstände</h2></div>
          <div className="inventory-table-wrap">
            {visibleItems.length===0?<div className="inventory-empty">Keine Gegenstände gefunden.</div>:
            <table className="inventory-table"><thead><tr>
              <SortHeader label="Inventar-Nr." keyName="inventory_number" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Name" keyName="name" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Typ" keyName="type" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Hostname" keyName="hostname" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Status" keyName="connectivity" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Inventarstatus" keyName="status" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Standort" keyName="location" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
              <SortHeader label="Letzter Kontakt" keyName="last_seen" sort={itemSort} onSort={k=>toggleSort(setItemSort,k)}/>
            </tr></thead><tbody>
              {visibleItems.map(item=>{const x=item as AnyRow;return <tr key={item.id} className={String(item.id)===String(selectedId)?'active':''}
                onClick={()=>void loadItemDetail(item.id)}>
                <td>{x.inventory_number||'—'}</td><td>{x.display_name||x.name||'—'}</td><td>{typeLabel(x.item_type)}</td>
                <td>{x.hostname||'—'}</td><td><span className={'inventory-status '+connectivityClass(x.connectivity_status)}>{connectivityLabel(x.connectivity_status)}</span></td>
                <td><span className="inventory-status lifecycle">{itemStatusLabel(x.status)}</span></td><td>{x.location_name||'—'}</td>
                <td>{formatDate(x.last_seen)}</td>
              </tr>})}
            </tbody></table>}
          </div>
        </section>
        <section className="inventory-detail" aria-live="polite">
          {!itemAny?<div className="inventory-empty">{items.length?'Wähle einen Gegenstand aus.':'Keine Gegenstände gefunden.'}</div>:<>
            <div className="inventory-detail-head"><div><h2>{itemAny.name||itemAny.inventory_number}</h2>
              <div className="muted compact">{itemAny.inventory_number||''} · {typeLabel(itemAny.item_type)}</div></div>
              {isAdmin&&<div className="inventory-detail-actions"><button className="button secondary" type="button" onClick={()=>setEditItem({...itemAny})}>Bearbeiten</button></div>}
            </div>
            <div className="inventory-detail-body">
              <div className="inventory-detail-grid">
                <DetailField label="Status" value={connectivityLabel(itemAny.connectivity_status)} className={'inventory-connectivity-value '+connectivityClass(itemAny.connectivity_status)}/>
                <DetailField label="Inventarstatus" value={itemStatusLabel(itemAny.status)}/><DetailField label="Hostname" value={itemAny.hostname}/>
                <DetailField label="Hersteller" value={itemAny.manufacturer}/><DetailField label="Modell" value={itemAny.model}/>
                <DetailField label="Seriennummer" value={itemAny.serial_number}/><DetailField label="SMBIOS UUID" value={itemAny.smbios_uuid}/>
                <DetailField label="Standort" value={itemAny.location_name}/><DetailField label={itemAny.current_user&&itemAny.connectivity_status==='ONLINE'?'Aktuell angemeldet':'Zuletzt angemeldet'} value={displayUser}/>
                <DetailField label="Betriebssystem" value={[itemAny.os_name,itemAny.os_version,itemAny.os_build].filter(Boolean).join(' ')}/>
                <DetailField label="BIOS" value={itemAny.bios_version}/><DetailField label="Domain" value={itemAny.domain}/>
                <DetailField label="Agent-Intervall" value={itemAny.agent_interval_seconds?itemAny.agent_interval_seconds+' s':'—'}/>
                <DetailField label="Letzter Kontakt" value={formatDate(itemAny.last_seen)}/>
                {knownUsers.length>0&&<div className="inventory-detail-field inventory-detail-field--wide inventory-user-field">
                  <details className="inventory-collapsible inventory-user-collapsible"><summary>
                    <span className="inventory-user-summary-text"><small>Letzte User</small><strong>{knownUsers.length} Benutzer</strong></span>
                    <span className="inventory-collapsible-count">{knownUsers.length}</span></summary>
                    <div className="inventory-user-list">{knownUsers.map((u,i)=><div key={i} className="inventory-user-row"><strong>{u.display_name||u.account_name}</strong></div>)}</div>
                  </details>
                </div>}
              </div>
              {itemAny.description&&<p>{itemAny.description}</p>}

              <section className="inventory-detail-section"><h3>Netzwerkschnittstellen</h3><div className="inventory-mini-list">
                {(itemAny.network_interfaces??[]).length===0?<div className="inventory-empty">Keine Netzwerkschnittstellen gemeldet.</div>:
                  (itemAny.network_interfaces as AnyRow[]).map((nic,i)=><MiniRow key={i} title={nic.interface_name||nic.mac_address||'Interface'} meta={nic.mac_address||'keine MAC'} right={nic.last_ip||'—'}/>)}
              </div></section>
              <section className="inventory-detail-section"><h3>Hardware</h3><div className="inventory-mini-list">
                {(itemAny.components??[]).length===0?<div className="inventory-empty">Noch keine Hardware-Komponenten gemeldet.</div>:
                  (itemAny.components as AnyRow[]).map((c,i)=><MiniRow key={c.id||i} title={typeLabel(c.component_type)} meta={[c.manufacturer,c.model,c.serial_number].filter(Boolean).join(' · ')||c.component_key} right={[capacityLabel(c.capacity_bytes),componentStatusLabel(c.component_status|| (c.active?'INSTALLED':'ARCHIVED'))].filter(x=>x!=='—').join(' · ')}/>)}
              </div></section>
              <section className="inventory-detail-section"><h3>Angeschlossene Geräte</h3><div className="inventory-mini-list">
                {(itemAny.peripherals??[]).length===0?<div className="inventory-empty">Keine angeschlossenen Peripheriegeräte gemeldet.</div>:
                  (itemAny.peripherals as AnyRow[]).map((x,i)=><MiniRow key={x.id||i} title={typeLabel(x.device_type)} meta={[[x.manufacturer,x.model].filter(Boolean).join(' '),x.serial_number?'S/N '+x.serial_number:'',x.device_uid].filter(Boolean).join(' · ')} right={[x.connection_type,x.identity_confidence].filter(Boolean).join(' · ')}/>)}
              </div></section>
              <section className="inventory-detail-section"><h3>Geräteverlauf</h3><div className="inventory-mini-list">
                {(itemAny.peripheral_connection_history??[]).length===0?<div className="inventory-empty">Noch kein Geräteverlauf vorhanden.</div>:
                  (itemAny.peripheral_connection_history as AnyRow[]).map((x,i)=><MiniRow key={x.connection_id||i} title={[x.manufacturer,x.model].filter(Boolean).join(' ')||typeLabel(x.device_type)} meta={[typeLabel(x.device_type),x.serial_number?'S/N '+x.serial_number:'',x.device_uid].filter(Boolean).join(' · ')} right={x.active?'Verbunden seit '+formatDate(x.connected_at):formatDate(x.connected_at)+' → '+formatDate(x.disconnected_at||x.last_seen)}/>)}
              </div></section>
              <section className="inventory-detail-section"><h3>Änderungshistorie</h3><div className="inventory-mini-list">
                {history.length===0?<div className="inventory-empty">Noch keine Änderungen protokolliert.</div>:history.map((x,i)=><div className="inventory-history-row" key={i}>
                  <strong>{x.field_name||'Änderung'}</strong><div><time>{formatDate(x.occurred_at)}</time><small> · {x.actor||'—'} · {x.source||'—'}</small></div>
                  <div>{show(x.old_value)} → {show(x.new_value)}</div></div>)}
              </div></section>
            </div>
          </>}
        </section>
      </div>
    </section>}

    {panel==='components'&&<section className="inventory-subpanel">
      <div className="inventory-metrics" aria-label="Komponentenübersicht">
        <Metric label="Gesamt" value={componentSummary.total}/><Metric label="Eingebaut" value={componentSummary.installed}/>
        <Metric label="Archiv" value={componentSummary.archived}/><Metric label="Prüfen" value={componentSummary.check_required}/>
        <Metric label="Defekt" value={componentSummary.defective}/><Metric label="Entsorgt" value={componentSummary.disposed}/>
      </div>
      <div className="inventory-filters">
        <label className="inventory-search"><span className="field-label">Komponenten suchen</span><input type="search"
          placeholder="Typ, Hersteller, Modell, Seriennummer, Rechner …" autoComplete="off" value={componentSearch} onChange={e=>setComponentSearch(e.target.value)}/></label>
        <label><span className="field-label">Status</span><select value={componentStatus} onChange={e=>setComponentStatus(e.target.value)}>
          <option value="all">Alle</option><option value="INSTALLED">Eingebaut</option><option value="ARCHIVED">Archiv</option>
          <option value="CHECK">Prüfen</option><option value="DEFECTIVE">Defekt</option><option value="DISPOSED">Entsorgt</option>
        </select></label>
        <label><span className="field-label">Typ</span><select value={componentType} onChange={e=>setComponentType(e.target.value)}>
          <option value="all">Alle</option><option value="CPU">CPU</option><option value="RAM">RAM</option><option value="DISK">Datenträger</option><option value="GPU">GPU</option>
        </select></label>
        <span className="count-badge inventory-count">{components.length}</span>
      </div>
      <div className="inventory-split inventory-component-split">
        <section className="inventory-list-panel inventory-component-panel"><div className="inventory-panel-head"><h2>Komponenten</h2></div>
          <div className="inventory-table-wrap">{visibleComponents.length===0?<div className="inventory-empty">Keine Komponenten gefunden.</div>:
          <table className="inventory-table inventory-component-table"><thead><tr>
            <SortHeader label="Typ" keyName="type" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            <SortHeader label="Hersteller" keyName="manufacturer" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            <SortHeader label="Kapazität" keyName="capacity" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            <SortHeader label="Status" keyName="status" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            <SortHeader label="Rechner" keyName="computer" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            <SortHeader label="Letzt erkannt" keyName="last_seen" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>
            {isAdmin&&<SortHeader label="Verwaltung" keyName="management" sort={componentSort} onSort={k=>toggleSort(setComponentSort,k)}/>}
          </tr></thead><tbody>{visibleComponents.map(c=>{const x=c as AnyRow;return <tr key={c.id} className={String(c.id)===String(componentId)?'active':''} onClick={()=>setComponentId(c.id)}>
            <td>{typeLabel(x.component_type)}</td><td>{x.manufacturer||'—'}</td><td>{capacityLabel(x.capacity_bytes)}</td>
            <td><span className={'inventory-component-status '+String(x.component_status||'').toLowerCase()}>{componentStatusLabel(x.component_status)}</span></td>
            <td>{x.computer_hostname||x.computer_inventory_number||x.computer_name||'—'}</td><td>{formatDate(x.last_seen)}</td>
            {isAdmin&&<td><select className="inventory-component-status-select" value={x.manual_status||'AUTO'}
              onClick={e=>e.stopPropagation()} onChange={e=>{e.stopPropagation();void updateComponent(c,e.target.value)}}>
              <option value="AUTO">Automatisch</option><option value="CHECK">Prüfen</option><option value="DEFECTIVE">Defekt</option><option value="DISPOSED">Entsorgt</option>
            </select></td>}
          </tr>})}</tbody></table>}</div>
        </section>
        <section className="inventory-detail" aria-live="polite">
          {!currentComponent?<div className="inventory-empty">{components.length?'Wähle eine Komponente aus.':'Keine Komponenten gefunden.'}</div>:
          <><div className="inventory-detail-head"><div><h2>{typeLabel(currentComponent.component_type)}</h2>
            <div className="muted compact">{[currentComponent.manufacturer,currentComponent.model].filter(Boolean).join(' · ')||'Komponente'}</div></div>
            {currentComponent.computer_item_id&&<div className="inventory-detail-actions"><button className="button secondary" type="button"
              onClick={()=>{setPanel('items');setSelectedId(currentComponent.computer_item_id!);setTimeout(()=>void loadItemDetail(currentComponent.computer_item_id!),0)}}>Rechner öffnen</button></div>}
          </div><div className="inventory-detail-body"><div className="inventory-detail-grid">
            <DetailField label="Typ" value={typeLabel(currentComponent.component_type)}/><DetailField label="Status" value={componentStatusLabel(currentComponent.component_status)}/>
            <DetailField label="Hersteller" value={currentComponent.manufacturer}/><DetailField label="Modell" value={currentComponent.model}/>
            <DetailField label="Seriennummer" value={currentComponent.serial_number}/><DetailField label="Kapazität" value={capacityLabel(currentComponent.capacity_bytes)}/>
            <DetailField label="Rechner" value={currentComponent.computer_hostname||currentComponent.computer_inventory_number||currentComponent.computer_name}/>
            <DetailField label="Erstmals erkannt" value={formatDate(currentComponent.first_seen)}/><DetailField label="Letzt erkannt" value={formatDate(currentComponent.last_seen)}/>
          </div>
          {currentComponent.attributes&&Object.keys(currentComponent.attributes).length>0&&<section className="inventory-detail-section"><h3>Technische Details</h3>
            <div className="inventory-mini-list">{Object.entries(currentComponent.attributes).filter(([,v])=>v!=null&&v!==''&&typeof v!=='object').map(([k,v])=>
              <MiniRow key={k} title={k.replaceAll('_',' ')} right={typeof v==='boolean'?(v?'Ja':'Nein'):String(v)}/>)}</div></section>}
          </div></>}
        </section>
      </div>
    </section>}

    {panel==='ip'&&<section className="inventory-subpanel">
      <div className="inventory-ip-toolbar"><label><span className="field-label">Netzwerk</span><select value={subnetId||''} onChange={e=>void selectSubnet(e.target.value)}>
        {subnets.map(s=><option key={s.id} value={s.id}>{s.cidr}</option>)}</select></label>
        <div id="inventoryIpSubnetMeta" className="muted compact">
          {subnet?<><span className="inventory-ip-meta-chip"><small>Netz</small><strong>{subnet.cidr}</strong></span>
            {subnet.gateway&&<span className="inventory-ip-meta-chip"><small>Gateway</small><strong>{subnet.gateway}</strong></span>}
            {subnet.vlan_id!=null&&<span className="inventory-ip-meta-chip"><small>VLAN</small><strong>{subnet.vlan_id}</strong></span>}
            {(subnet.dhcp_start||subnet.dhcp_end)&&<span className="inventory-ip-meta-chip"><small>DHCP-Bereich</small><strong>{subnet.dhcp_start||'—'} - {subnet.dhcp_end||'—'}</strong></span>}
          </>:<>Noch kein Netzwerk angelegt.</>}</div>
      </div>
      <div className="inventory-metrics" aria-label="IP-Übersicht">
        <Metric label="Adressen" value={ipCounts.total}/><Metric label="Belegt" value={ipCounts.occupied}/><Metric label="Frei" value={ipCounts.free}/>
        <Metric label="Reserviert" value={ipCounts.reserved}/><Metric label="Konflikte" value={ipCounts.conflicts}/><Metric label="Automatisch erkannt" value={ipCounts.observed}/>
      </div>
      <div className="inventory-filters">
        <label className="inventory-search"><span className="field-label">IP-Adressen suchen</span><input type="search"
          placeholder="IP, Hostname, Inventar-Nr., MAC …" autoComplete="off" value={ipSearch} onChange={e=>setIpSearch(e.target.value)}/></label>
        <label><span className="field-label">Status</span><select value={ipStatus} onChange={e=>setIpStatus(e.target.value)}>
          <option value="all">Alle</option><option value="OCCUPIED">Belegt</option><option value="FREE">Frei</option><option value="RESERVED">Reserviert</option>
          <option value="CONFLICT">Konflikt</option><option value="GATEWAY">Gateway</option><option value="NETWORK">Netzwerkadresse</option><option value="BROADCAST">Broadcast</option>
        </select></label>
        <label><span className="field-label">Bereich</span><select value={ipPool} onChange={e=>setIpPool(e.target.value)}>
          <option value="all">Alle Bereiche</option><option value="DHCP">Im DHCP-Bereich</option><option value="OUTSIDE">Außerhalb DHCP-Bereich</option>
        </select></label>
        <label><span className="field-label">Quelle</span><select value={ipSource} onChange={e=>setIpSource(e.target.value)}>
          <option value="all">Alle Quellen</option><option value="dhcp">Windows-DHCP</option><option value="zyxel-dhcp">Zyxel-DHCP</option>
          <option value="dns">DNS</option><option value="icmp-scan">ICMP-Scan</option><option value="windows-agent">Inventory-Agent</option><option value="manual">Manuell</option>
        </select></label>
        <span className="count-badge inventory-count">{filteredIps.length}</span>
      </div>
      <section className="inventory-list-panel inventory-ip-panel"><div className="inventory-panel-head"><h2>IP-Adressen</h2></div>
        <div className="inventory-table-wrap">{filteredIps.length===0?<div className="inventory-empty">{subnetId?'Keine IP-Adressen für diesen Filter gefunden.':'Noch kein Netzwerk angelegt.'}</div>:
        <table className="inventory-table inventory-ip-table"><thead><tr>
          <SortHeader label="IP-Adresse" keyName="ip_address" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="Status" keyName="status" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="Hostname" keyName="hostname" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="Gerät" keyName="device" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="MAC" keyName="mac_address" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="Quelle" keyName="source" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
          <SortHeader label="Zuletzt gesehen" keyName="last_seen" sort={ipSort} onSort={k=>toggleSort(setIpSort,k)}/>
        </tr></thead><tbody>{filteredIps.map(row=>{const pool=inPool(row.ip_address,subnet);return <tr key={row.ip_address}
          className={(row.status==='CONFLICT'?'inventory-ip-conflict ':'')+(pool?'inventory-ip-dhcp-row ':'')+(row.status==='RESERVED'?'inventory-ip-reserved-row':'')}>
          <td><div className="inventory-ip-address-cell"><strong>{row.ip_address}</strong>{pool&&<span className="inventory-ip-pool-badge">DHCP-Pool</span>}</div></td>
          <td><span className={'inventory-ip-status '+String(row.status||'').toLowerCase()}>{ipStatusLabel(row.status)}</span></td>
          <td title={row.status==='RESERVED'?row.description||'':undefined}>{row.hostname||row.label||'—'}</td><td>{row.device||row.label||'—'}</td>
          <td>{row.mac_address||'—'}</td><td>{row.source||'—'}</td><td>{formatDate(row.last_seen)}</td>
        </tr>})}</tbody></table>}</div>
      </section>
    </section>}

    {editItem&&<div className="modal-backdrop" style={{display:'flex'}}>
      <form className="modal inventory-item-modal" onSubmit={saveItem}>
        <h2>{editItem.id?'Inventargegenstand bearbeiten':'Inventargegenstand anlegen'}</h2>
        <div className="form-grid two-col">
          <label><span className="field-label">Inventar-Nr.</span><input value={editItem.inventory_number||''} onChange={e=>setEditItem({...editItem,inventory_number:e.target.value})}/></label>
          <label><span className="field-label">Interner Gegenstandsname</span><input required value={editItem.name||''} onChange={e=>setEditItem({...editItem,name:e.target.value})}/></label>
          <label><span className="field-label">Typ</span><select value={editItem.item_type||'COMPUTER'} onChange={e=>setEditItem({...editItem,item_type:e.target.value})}>
            <option value="COMPUTER">Computer</option><option value="LAPTOP">Laptop</option><option value="SERVER">Server</option><option value="MONITOR">Monitor</option>
            <option value="NETWORK">Netzwerkgerät</option><option value="PERIPHERAL">Peripherie</option><option value="OTHER">Sonstiges</option></select></label>
          <label><span className="field-label">Inventarstatus</span><select value={editItem.status||'ACTIVE'} onChange={e=>setEditItem({...editItem,status:e.target.value})}>
            <option value="ACTIVE">Aktiv</option><option value="STORAGE">Lager</option><option value="REPAIR">Reparatur</option><option value="RETIRED">Ausgemustert</option><option value="DISPOSED">Entsorgt</option>
          </select></label>
          {([['manufacturer','Hersteller'],['model','Modell'],['serial_number','Seriennummer'],['assigned_to','Manuelle Zuordnung (optional)'],['hostname','Hostname'],['smbios_uuid','SMBIOS UUID'],['domain','Domain']] as const).map(([key,label])=>
            <label key={key}><span className="field-label">{label}</span><input value={editItem[key]||''} onChange={e=>setEditItem({...editItem,[key]:e.target.value})}/></label>)}
          <label><span className="field-label">Standort</span><select value={editItem.location_id||''} onChange={e=>setEditItem({...editItem,location_id:e.target.value})}>
            <option value="">—</option>{locations.map(l=><option key={l.id} value={l.id}>{l.code?l.code+' · ':''}{l.name}</option>)}</select></label>
          <label><span className="field-label">Kaufdatum</span><input type="date" value={editItem.purchase_date||''} onChange={e=>setEditItem({...editItem,purchase_date:e.target.value})}/></label>
          <label><span className="field-label">Kaufpreis (€)</span><input type="number" min="0" step="0.01" value={editItem.purchase_price||''} onChange={e=>setEditItem({...editItem,purchase_price:e.target.value})}/></label>
          <label><span className="field-label">Garantie bis</span><input type="date" value={editItem.warranty_until||''} onChange={e=>setEditItem({...editItem,warranty_until:e.target.value})}/></label>
          <label className="span-2"><span className="field-label">Beschreibung</span><textarea rows={3} value={editItem.description||''} onChange={e=>setEditItem({...editItem,description:e.target.value})}/></label>
          <label className="span-2"><span className="field-label">Notizen</span><textarea rows={4} value={editItem.notes||''} onChange={e=>setEditItem({...editItem,notes:e.target.value})}/></label>
        </div>
        <div className="modal-actions"><button className="button secondary" type="button" onClick={()=>setEditItem(null)}>Abbrechen</button>
          <button className="button" disabled={busy}>Speichern</button></div>
      </form>
    </div>}
  </section>;
}
