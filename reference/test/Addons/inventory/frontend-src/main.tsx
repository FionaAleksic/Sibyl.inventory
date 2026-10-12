import React from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {InventoryPage} from '../../../frontend-next/src/modules/inventory/pages/InventoryPage';
import {configureInventoryApiBase,resetInventoryApiBase} from '../../../frontend-next/src/modules/inventory/services/inventoryService';

interface ModuleContext {
  moduleId:string;
  version:string;
  admin:boolean;
  query:Record<string,string>;
  navigateModule:(moduleId:string,query?:Record<string,string>)=>void;
  apiBase:string;
  adminApiBase:string;
}

const roots=new WeakMap<HTMLElement,Root>();

export function mount(container:HTMLElement,context:ModuleContext):void{
  unmount(container);
  configureInventoryApiBase(context.apiBase);
  const root=createRoot(container);
  roots.set(container,root);
  root.render(<React.StrictMode><InventoryPage isAdmin={context.admin}/></React.StrictMode>);
}

export function unmount(container:HTMLElement):void{
  const root=roots.get(container);
  if(root){root.unmount();roots.delete(container);}
  resetInventoryApiBase();
}
