import {api} from '../../../shared/services/apiClient';
import type {InventoryItem,InventoryComponent,InventoryPeripheral,InventorySubnet,InventoryLocation,IpAddress} from '../../../shared/types';

let inventoryApiBase='/api/inventory';
export function configureInventoryApiBase(base:string){
  const clean=base.trim().replace(/\/+$/,'');
  inventoryApiBase=clean||'/api/inventory';
}
export function resetInventoryApiBase(){inventoryApiBase='/api/inventory';}

const path=(suffix:string)=>inventoryApiBase+suffix;
const q=(params:Record<string,string|number>)=>'?'+new URLSearchParams(Object.entries(params).map(([key,value])=>[key,String(value)])).toString();
const item=(id:string)=>path('/items/'+encodeURIComponent(id));

export const inventoryService={
  summary:()=>api<Record<string,unknown>>(path('/summary')),
  list:(params:Record<string,string|number>={})=>api<InventoryItem[]>(path('/items')+q({limit:250,...params})),
  get:(id:string)=>api<InventoryItem>(item(id)),
  create:(data:Record<string,unknown>)=>api<InventoryItem>(path('/items'),{method:'POST',json:data}),
  update:(id:string,data:Record<string,unknown>)=>api<InventoryItem>(item(id),{method:'PUT',json:data}),
  history:(id:string)=>api<Array<Record<string,unknown>>>(item(id)+'/history?limit=100'),
  componentSummary:()=>api<Record<string,unknown>>(path('/components/summary')),
  components:(params:Record<string,string|number>={})=>api<InventoryComponent[]>(path('/components')+q({limit:500,...params})),
  componentStatus:(id:string,status:string)=>api<InventoryComponent>(path('/components/'+encodeURIComponent(id)+'/status'),{
    method:'PUT',json:{status}}),
  peripherals:(query:string='')=>api<InventoryPeripheral[]>(path('/peripherals')+q({q:query,limit:500})),
  subnets:()=>api<InventorySubnet[]>(path('/ip/subnets')),
  addresses:(id:string,params:Record<string,string|number>={})=>api<IpAddress[]>(path('/ip/subnets/'+encodeURIComponent(id)+'/addresses')+q(params)),
  updateSubnet:(id:string,data:Record<string,unknown>)=>api<InventorySubnet>(path('/ip/subnets/'+encodeURIComponent(id)),{method:'PUT',json:data}),
  locations:()=>api<InventoryLocation[]>(path('/locations')),
  createLocation:(data:Record<string,unknown>)=>api<InventoryLocation>(path('/locations'),{method:'POST',json:data}),
  updateLocation:(id:string,data:Record<string,unknown>)=>api<InventoryLocation>(path('/locations/'+encodeURIComponent(id)),{method:'PUT',json:data}),
  numberSchemes:()=>api<Array<Record<string,unknown>>>(path('/number-schemes')),
  updateNumberScheme:(type:string,data:Record<string,unknown>)=>api<Record<string,unknown>>(path('/number-schemes/'+encodeURIComponent(type)),{method:'PUT',json:data})
};
