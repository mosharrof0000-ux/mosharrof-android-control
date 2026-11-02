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
  if(req.method==="GET"&&url.pathname==="/healthz")return json({status:"ok",service:"mosharrof-chatgpt-device-relay",stage:"unpaired-or-paired"});
  if(!env.CHATGPT_CONNECTOR_TOKEN||!env.DEVICE_SETUP_TOKEN)return json({error:"relay_secrets_not_configured"},503);
  const id=env.RELAY.idFromName("single-authorized-device");
  return env.RELAY.get(id).fetch(req);
 }
};
const ALLOWED=new Set(["termux.python_version","termux.git_video_status","termux.storage_report","termux.system_info","device.storage_report","device.selected_folder_inventory","device.audio_metadata"]);
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
   // Read-and-pop atomically: concurrent device polls must not receive the same job.
   const job=await this.state.storage.transaction(async tx=>{
    const queue=await tx.get<Array<any>>("queue")??[];
    const now=Date.now(),live=queue.filter(j=>j.expires_at>now);
    const next=live.shift()??null;
    await tx.put("queue",live);
    return next;
   });
   return json({job});
  }
  const resultMatch=p.match(/^\/v1\/device\/jobs\/([a-f0-9-]+)\/result$/);
  if(resultMatch&&req.method==="POST"){
   if(!await this.deviceAuth(req))return json({error:"unauthorized"},401);
   const body=await req.json().catch(()=>null) as any;
   if(!body||typeof body.output!=="string"||body.output.length>12000||!Number.isInteger(body.exit_code))return json({error:"invalid_result"},400);
   const key="result:"+resultMatch[1];if(await this.state.storage.get(key))return json({error:"duplicate_result"},409);
   await this.state.storage.put(key,{command_id:resultMatch[1],output:body.output,exit_code:body.exit_code,completed_at:new Date().toISOString()},{expirationTtl:86400});
   return json({accepted:true});
  }
  if(p==="/v1/chat/commands"&&req.method==="POST"){
   if(!equal(bearer(req),this.env.CHATGPT_CONNECTOR_TOKEN))return json({error:"unauthorized"},401);
   if(!await this.state.storage.get<boolean>("paired"))return json({error:"device_not_paired"},409);
   const body=await req.json().catch(()=>null) as any;
   if(!body||typeof body.operation_id!=="string"||!ALLOWED.has(body.operation_id))return json({error:"operation_not_allowed"},400);
   const idem=typeof body.idempotency_key==="string"?body.idempotency_key.slice(0,100):"";
   // Queue capacity, idempotency and insertion share one transaction to prevent
   // concurrent connector requests from exceeding the bound or duplicating jobs.
   const outcome=await this.state.storage.transaction(async tx=>{
    if(idem){
     const prior=await tx.get<string>("idem:"+idem);
     if(prior)return {kind:"reused" as const,command_id:prior};
    }
    const queue=await tx.get<Array<any>>("queue")??[];
    const live=queue.filter(j=>j.expires_at>Date.now());
    if(live.length>=MAX_QUEUE)return {kind:"full" as const};
    const job={command_id:crypto.randomUUID(),operation_id:body.operation_id,created_at:new Date().toISOString(),expires_at:Date.now()+JOB_TTL};
    live.push(job);
    await tx.put("queue",live);
    if(idem)await tx.put("idem:"+idem,job.command_id,{expirationTtl:86400});
    await tx.put("audit:"+job.command_id,{...job,actor:"chatgpt-connector"},{expirationTtl:604800});
    return {kind:"created" as const,job};
   });
   if(outcome.kind==="full")return json({error:"queue_full"},429);
   if(outcome.kind==="reused")return json({command_id:outcome.command_id,reused:true},200);
   return json(outcome.job,202);
  }
  const getMatch=p.match(/^\/v1\/chat\/commands\/([a-f0-9-]+)$/);
  if(getMatch&&req.method==="GET"){
   if(!equal(bearer(req),this.env.CHATGPT_CONNECTOR_TOKEN))return json({error:"unauthorized"},401);
   const result=await this.state.storage.get("result:"+getMatch[1]);
   return result?json({status:"completed",...result}):json({status:"pending_or_expired",command_id:getMatch[1]},202);
  }
  if(p==="/v1/device/unpair"&&req.method==="POST"){
   if(!await this.deviceAuth(req))return json({error:"unauthorized"},401);
   await this.state.storage.deleteAll();return json({paired:false,revoked:true});
  }
  return json({error:"not_found"},404);
 }
 async deviceAuth(req:Request){const token=bearer(req);const saved=await this.state.storage.get<string>("deviceToken");return !!saved&&equal(token,saved)&&await this.state.storage.get<boolean>("paired")===true;}
}
