'use strict';
(()=>{let seq=0;const pending=new Map();
window.addEventListener('message',e=>{
 if(e.source!==parent||e.data?.sibyl!=='records-response')return;
 const w=pending.get(e.data.requestId);if(!w)return;
 clearTimeout(w.timer);pending.delete(e.data.requestId);
 if(e.data.error)w.reject(Error(e.data.error));else w.resolve(e.data.data);
});
function call(op,collection,fields={}){
 if(!/^[a-z][a-z0-9-]{0,48}$/.test(collection))return Promise.reject(Error('Invalid collection'));
 return new Promise((resolve,reject)=>{
  const requestId=++seq;
  const timer=setTimeout(()=>{pending.delete(requestId);reject(Error('Timeout'))},12000);
  pending.set(requestId,{resolve,reject,timer});
  parent.postMessage({sibyl:'records',requestId,op,collection,...fields},'*');
 });
}
window.SibylBridge=Object.freeze({
 list:c=>call('list',c),get:(c,id)=>call('get',c,{id}),
 history:(c,id)=>call('history',c,{id}),
 create:(c,value)=>call('create',c,{value}),
 update:(c,id,expectedVersion,value)=>call('update',c,{id,expectedVersion,value}),
 archive:(c,id,expectedVersion)=>call('archive',c,{id,expectedVersion})
});
})();