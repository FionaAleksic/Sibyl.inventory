import type {InventoryItem} from '../../../shared/types';
import {componentSizeLabel,componentStatusLabel} from '../services/componentPresentation';
const show=(value:unknown)=>value==null||value===''?'–':String(value);
/** Uses the complete authenticated /items/{id} response; never guesses associations from the global list. */
export function InventoryDeviceDetails({item}:{item:InventoryItem}){
 const network=item.network_interfaces??[];
 const components=item.components??[];
 const peripherals=item.peripherals??[];
 const history=item.peripheral_connection_history??[];
 return <div className="inventory-device-details">
  <section aria-label="Netzwerkschnittstellen"><h3>Netzwerkschnittstellen ({network.length})</h3>
   <div className="scroll-table"><table><thead><tr><th>Name</th><th>MAC</th><th>Letzte IP</th><th>Aktiv</th></tr></thead>
    <tbody>{network.map((row,i)=><tr key={show(row.id)+i}><td>{show(row.interface_name)}</td>
      <td>{show(row.mac_address)}</td><td>{show(row.last_ip)}</td><td>{row.active?'Ja':'Nein'}</td></tr>)}</tbody></table></div>
  </section>
  <section aria-label="Gerätekomponenten"><h3>Komponenten ({components.length})</h3>
   <div className="scroll-table"><table><thead><tr><th>Typ</th><th>Modell</th><th>Seriennummer</th><th>Kapazität</th><th>Status</th></tr></thead>
    <tbody>{components.map((c,i)=><tr key={c.id??String(i)}><td>{show(c.component_type)}</td>
      <td>{show(c.model)}</td><td>{show(c.serial_number)}</td>
      <td>{componentSizeLabel(c.capacity_bytes)}</td><td>{componentStatusLabel(c.component_status)}</td></tr>)}</tbody></table></div>
  </section>
  <section aria-label="Angeschlossene Peripherie"><h3>Angeschlossene Peripherie ({peripherals.length})</h3>
   <div className="scroll-table"><table><thead><tr><th>Typ</th><th>Modell</th><th>Geräte-ID</th><th>Identität</th><th>Seriennummer</th></tr></thead>
    <tbody>{peripherals.map((p,i)=><tr key={p.id??String(i)}><td>{show(p.device_type)}</td>
      <td>{show(p.model)}</td><td>{show(p.device_uid)}</td>
      <td>{show(p.identity_confidence)}</td><td>{show(p.serial_number)}</td></tr>)}</tbody></table></div>
  </section>
  <section aria-label="Peripherie-Verbindungsverlauf"><h3>Peripherie-Verbindungsverlauf ({history.length})</h3>
   <div className="scroll-table"><table><thead><tr><th>Gerät</th><th>Verbunden</th><th>Zuletzt gesehen</th><th>Getrennt</th></tr></thead>
    <tbody>{history.map((row,i)=><tr key={show(row.connection_id)+i}><td>{show(row.device_uid)}</td>
      <td>{show(row.connected_at)}</td><td>{show(row.last_seen)}</td><td>{show(row.disconnected_at)}</td></tr>)}</tbody></table></div>
  </section>
 </div>;
}
