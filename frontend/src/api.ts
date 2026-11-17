export async function api<T>(path:string,init:RequestInit={},timeout=180000):Promise<T>{
const controller=new AbortController();const timer=window.setTimeout(()=>controller.abort(),timeout);
try{const response=await fetch(`/api${path}`,{...init,signal:controller.signal,headers:{...(init.body instanceof FormData?{}:{'Content-Type':'application/json'}),...init.headers}});const text=await response.text();let payload;try{payload=JSON.parse(text);}catch{throw new Error('服务暂时不可用，请检查后端是否已启动。');}if(!response.ok||payload.success!==true)throw new Error(payload.errorMsg||`请求未完成（${response.status}）`);return payload.data as T;}
catch(error){if(error instanceof DOMException&&error.name==='AbortError')throw new Error('等待响应超时，请刷新会话确认结果，避免重复发送。');if(error instanceof TypeError)throw new Error('无法连接服务，请检查后端是否已启动。');throw error;}finally{window.clearTimeout(timer);}}
export const post=<T>(path:string,body?:unknown)=>api<T>(path,{method:'POST',body:body===undefined?undefined:JSON.stringify(body)});
export const errorText=(e:unknown)=>e instanceof Error?e.message:'操作未完成，请稍后重试';
