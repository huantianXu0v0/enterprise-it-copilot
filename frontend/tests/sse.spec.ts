import {test,expect} from '@playwright/test';
import {readSse,streamAsk} from '../src/sse';

test('SSE parser handles byte-split Chinese, CRLF, multiline data and heartbeats',async()=>{
 const bytes=new TextEncoder().encode(': ping\r\nevent: delta\r\ndata: 第一行\r\ndata:  第二行\r\n\r\n');
 let offset=0;
 const stream=new ReadableStream<Uint8Array>({pull(c){if(offset<bytes.length)c.enqueue(bytes.slice(offset,++offset));else c.close();}});
 const events:unknown[]=[];
 await readSse(stream,event=>{events.push(event);});
 expect(events).toEqual([{event:'delta',data:'第一行\n 第二行'}]);
});

test('EOF after a delta never counts as success',async()=>{
 const original=globalThis.fetch;
 globalThis.fetch=async()=>new Response('event: delta\ndata: {"text":"部分回答"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
 try{
   const chunks:string[]=[];
   await expect(streamAsk('c',{question:'问题',topK:5,minScore:.3,deepThinking:false},new AbortController().signal,(_,text)=>chunks.push(text))).rejects.toThrow('连接提前结束');
   expect(chunks).toEqual(['部分回答']);
 }finally{globalThis.fetch=original;}
});

test('SSE error is surfaced even after partial answer',async()=>{
 const original=globalThis.fetch;
 globalThis.fetch=async()=>new Response('event: delta\ndata: {"text":"部分"}\n\nevent: error\ndata: {"message":"模型连接中断"}\n\n',{headers:{'Content-Type':'text/event-stream'}});
 try{
   await expect(streamAsk('c',{question:'问题',topK:5,minScore:.3,deepThinking:true},new AbortController().signal,()=>{})).rejects.toThrow('模型连接中断');
 }finally{globalThis.fetch=original;}
});
