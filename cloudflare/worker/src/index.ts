export interface Env {
  RELAY: DurableObjectNamespace;
  CHATGPT_CONNECTOR_TOKEN: string;
  DEVICE_SETUP_TOKEN: string;
}
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{"content-type":"application/json; charset=utf-8","cache-control":"no-store"}});
const equal=(a:string,b:string)=>{const x=new TextEncoder().encode(a),y=new TextEncoder().encode(b);if(x.length!==y.length)return false;let n=0;for(let i=0;i<x.length;i++)n|=x[i]^y[i];return n===0;};
function bearer(req:Request){return req.headers.get("authorization")?.replace(/^Bearer\s+/i,"")??"";}
export default {
 async fetch(req:Request,env:Env):Promise<Response>{
  const url=new URL(req.url);
  if(req.method==="GET"&&url.pathname==="/healthz")return json({status:"ok",service:"mosharrof-chatgpt-device-relay",stage:"unpaired-or-paired",secrets_configured:!!(env.CHATGPT_CONNECTOR_TOKEN&&env.DEVICE_SETUP_TOKEN)});
  if(!env.CHATGPT_CONNECTOR_TOKEN||!env.DEVICE_SETUP_TOKEN)return json({error:"relay_secrets_not_configured"},503);
  const id=env.RELAY.idFromName("single-authorized-device");
  return env.RELAY.get(id).fetch(req);
 }
};
const ALLOWED=new Set(["termux.python_version","termux.git_video_status","termux.storage_report","termux.system_info"]);
const MAX_QUEUE=20, JOB_TTL=60_000;
export class RelayDO {
 state:DurableObjectState; env:Env;
 constructor(state:DurableObjectState,env:Env){this.state=state;this.env=env;}
 async fetch(req:Request):Promise<Response>{
  const u=new URL(req.url),p=u.pathname;
  if(p==="/v1/device/pair/start"&&req.method==="POST"){
   if(!equal(bearer(req)||req.headers.get("x-device-setup-token")||"",this.env.DEVICE_SETUP_TOKEN))return json({error:"unauthorized"},401);
   const code=String(crypto.getRandomValues(new Uint32Array(1))[0]%1_000_000).padStart(6,"0");
   const token=Array.from(crypto.getRandomValues(new Uint8Array(32)),b=>b.toString(16).padStart(2,"0")).join("");
   await this.state.storage.put("pairing",{code,expires:Date.now()+300_000});
   await this.state.storage.put("deviceToken",token);
   await this.state.storage.put("paired",false);
   return json({pairing_code:code,expires_in_seconds:300,device_token:token});
  }
  if(p==="/v1/chat/pair/claim"&&req.method==="POST"){
   if(!equal(bearer(req),this.env.CHATGPT_CONNECTOR_TOKEN))return json({error:"unauthorized"},401);
   const body=await req.json().catch(()=>null) as {pairing_code?:string}|null;
   const pair=await this.state.storage.get<{code:string;expires:number}>("pairing");
   if(!body?.pairing_code||!pair||pair.expires<Date.now()||!equal(body.pairing_code,pair.code))return json({error:"invalid_or_expired_pairing_code"},403);
   await this.state.storage.put("paired",true);await this.state.storage.delete("pairing");
   return json({paired:true,device_id:"primary"});
  }
  if(p==="/v1/device/jobs/next"&&req.method==="GET"){
   if(!await this.deviceAuth(req))return json({error:"unauthorized"},401);
   if(!await this.allowRateLimit("device:poll",30,60_000))return json({error:"rate_limited"},429);
   // Lease instead of destructive read-and-pop. An unacknowledged job becomes
   // visible again after its lease expires, while concurrent polls remain atomic.
   const job=await this.state.storage.transaction(async tx=>{
    const queue=await tx.get<Array<any>>("queue")??[];
    const now=Date.now(),expired=queue.filter(j=>j.expires_at<=now);
    const live=queue.filter(j=>j.expires_at>now);
    for(const item of expired){
     const audit=await tx.get<any>("audit:"+item.command_id);
     if(audit&&audit.status!=="completed")await tx.put("audit:"+item.command_id,{...audit,status:"expired",expired_at:new Date(now).toISOString()});
    }
    const index=live.findIndex(j=>!j.lease_until||j.lease_until<=now);
    let next=null;
    if(index>=0){
     const current=live[index],attempts=(current.attempts??0)+1;
     next={...current,attempts,lease_until:now+Math.min(30_000,15_000*attempts)};
     live[index]=next;
     const audit=await tx.get<any>("audit:"+current.command_id);
     if(audit)await tx.put("audit:"+current.command_id,{...audit,status:"leased",attempts,lease_until:next.lease_until});
    }
    await tx.put("queue",live);
    return next;
   });
   return json({job:job?{command_id:job.command_id,operation_id:job.operation_id,created_at:job.created_at,expires_at:job.expires_at,attempts:job.attempts}:null});
  }
  const resultMatch=p.match(/^\/v1\/device\/jobs\/([a-f0-9-]+)\/result$/);
  if(resultMatch&&req.method==="POST"){
   if(!await this.deviceAuth(req))return json({error:"unauthorized"},401);
   if(!await this.allowRateLimit("device:result",30,60_000))return json({error:"rate_limited"},429);
   const body=await req.json().catch(()=>null) as any;
   if(!body||typeof body.output!=="string"||body.output.length>12000||!Number.isInteger(body.exit_code))return json({error:"invalid_result"},400);
   const fingerprintBytes=await crypto.subtle.digest(
    "SHA-256",new TextEncoder().encode(JSON.stringify([body.exit_code,body.output]))
   );
   const fingerprint=Array.from(new Uint8Array(fingerprintBytes),b=>b.toString(16).padStart(2,"0")).join("");
   const outcome=await this.state.storage.transaction(async tx=>{
    const commandId=resultMatch[1],key="result:"+commandId,auditKey="audit:"+commandId;
    const prior=await tx.get<any>(key);
    if(prior){
     return prior.output===body.output&&prior.exit_code===body.exit_code
      ?{kind:"reused" as const}:{kind:"conflict" as const};
    }
    const audit=await tx.get<any>(auditKey);
    if(!audit||audit.expires_at<=Date.now())return {kind:"unknown" as const};
    if(audit.status==="completed"){
     return audit.result_fingerprint===fingerprint
      ?{kind:"reused" as const}:{kind:"conflict" as const};
    }
    const completedAt=new Date().toISOString();
    await tx.put(key,{command_id:commandId,output:body.output,exit_code:body.exit_code,completed_at:completedAt,expires_at:Date.now()+86400_000});
    const queue=await tx.get<Array<any>>("queue")??[];
    await tx.put("queue",queue.filter(j=>j.command_id!==commandId));
    await tx.put(auditKey,{...audit,status:"completed",exit_code:body.exit_code,result_fingerprint:fingerprint,completed_at:completedAt});
    return {kind:"accepted" as const};
   });
   if(outcome.kind==="conflict")return json({error:"result_conflict"},409);
   if(outcome.kind==="unknown")return json({error:"unknown_or_expired_command"},404);
   return json({accepted:true,reused:outcome.kind==="reused"});
  }
  if(p==="/v1/chat/commands"&&req.method==="POST"){
   if(!equal(bearer(req),this.env.CHATGPT_CONNECTOR_TOKEN))return json({error:"unauthorized"},401);
   if(!await this.state.storage.get<boolean>("paired"))return json({error:"device_not_paired"},409);
   if(!await this.allowRateLimit("connector:commands",20,60_000))return json({error:"rate_limited"},429);
   const body=await req.json().catch(()=>null) as any;
   if(!body||typeof body.operation_id!=="string"||!ALLOWED.has(body.operation_id))return json({error:"operation_not_allowed"},400);
   const idem=typeof body.idempotency_key==="string"?body.idempotency_key.trim():"";
   if(!/^[A-Za-z0-9._:-]{8,100}$/.test(idem))return json({error:"idempotency_key_required"},400);
   // Queue capacity, idempotency and insertion share one transaction to prevent
   // concurrent connector requests from exceeding the bound or duplicating jobs.
   const outcome=await this.state.storage.transaction(async tx=>{
    const prior=await tx.get<{command_id:string;expires_at:number}>("idem:"+idem);
    if(prior && prior.expires_at>Date.now())return {kind:"reused" as const,command_id:prior.command_id};
    const queue=await tx.get<Array<any>>("queue")??[];
    const live=queue.filter(j=>j.expires_at>Date.now());
    if(live.length>=MAX_QUEUE)return {kind:"full" as const};
    const job={command_id:crypto.randomUUID(),operation_id:body.operation_id,created_at:new Date().toISOString(),expires_at:Date.now()+JOB_TTL,attempts:0,lease_until:0};
    live.push(job);
    await tx.put("queue",live);
    await tx.put("idem:"+idem,{command_id:job.command_id,expires_at:Date.now()+86400_000});
    await tx.put("audit:"+job.command_id,{...job,actor:"chatgpt-connector",status:"queued",expires_at:Date.now()+604800_000});
    return {kind:"created" as const,job};
   });
   if(outcome.kind==="full")return json({error:"queue_full"},429);
   if(outcome.kind==="reused")return json({command_id:outcome.command_id,reused:true},200);
   return json(outcome.job,202);
  }
  const getMatch=p.match(/^\/v1\/chat\/commands\/([a-f0-9-]+)$/);
  if(getMatch&&req.method==="GET"){
   if(!equal(bearer(req),this.env.CHATGPT_CONNECTOR_TOKEN))return json({error:"unauthorized"},401);
   if(!await this.allowRateLimit("connector:status",60,60_000))return json({error:"rate_limited"},429);
   const commandId=getMatch[1];
   const result=await this.state.storage.get<{expires_at:number;[key:string]:unknown}>("result:"+commandId);
   if(result&&result.expires_at>Date.now())return json({status:"completed",...result});
   const audit=await this.state.storage.get<any>("audit:"+commandId);
   if(audit?.status==="completed")return json({status:"completed_result_expired",command_id:commandId,completed_at:audit.completed_at,exit_code:audit.exit_code});
   if(audit?.status==="expired")return json({status:"expired",command_id:commandId,expired_at:audit.expired_at},410);
   const queue=await this.state.storage.get<Array<any>>("queue")??[];
   const job=queue.find(item=>item.command_id===commandId&&item.expires_at>Date.now());
   if(job)return json({status:job.lease_until>Date.now()?"leased":"queued",command_id:commandId,expires_at:job.expires_at,attempts:job.attempts},202);
   return json({status:"expired_or_unknown",command_id:commandId},404);
  }
  if(p==="/v1/device/unpair"&&req.method==="POST"){
   if(!await this.deviceAuth(req))return json({error:"unauthorized"},401);
   await this.state.storage.deleteAll();return json({paired:false,revoked:true});
  }
  return json({error:"not_found"},404);
 }
 async allowRateLimit(key:string,limit:number,windowMs:number):Promise<boolean>{
  return this.state.storage.transaction(async tx=>{
   const now=Date.now(),storageKey="rate:"+key;
   const current=await tx.get<{window_start:number;count:number}>(storageKey);
   if(!current||now-current.window_start>=windowMs){
    await tx.put(storageKey,{window_start:now,count:1});return true;
   }
   if(current.count>=limit)return false;
   await tx.put(storageKey,{...current,count:current.count+1});return true;
  });
 }
 async deviceAuth(req:Request){const token=bearer(req);const saved=await this.state.storage.get<string>("deviceToken");return !!saved&&equal(token,saved)&&await this.state.storage.get<boolean>("paired")===true;}
}
