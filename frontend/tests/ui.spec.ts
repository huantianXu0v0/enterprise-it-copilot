import {test,expect} from '@playwright/test';
const ok=(data:unknown)=>({success:true,errorMsg:null,data});
test.beforeEach(async({page})=>{
 await page.route('**/api/**',async route=>{
  const url=new URL(route.request().url());
  let data:unknown=[];
  if(url.pathname==='/api/conversations') data={conversationId:'test-conversation'};
  if(url.pathname.endsWith('/ask/stream')) data={answer:'安全回答 <img src=x onerror="window.__xss=true">',plan:{status:'matched',originalQuestion:'你好',rewrittenQuestion:'你好',tasks:[]},sources:[],branches:[]};
  if(url.pathname==='/api/workspace/conversations') data=Array.from({length:30},(_,i)=>({conversationId:String(i),title:'历史对话 '+i}));
  if(url.pathname.endsWith('/ask/stream')){
    const answer=data as {answer:string};
    await route.fulfill({contentType:'text/event-stream',body:'event: delta\ndata: '+JSON.stringify({text:answer.answer})+'\n\nevent: done\ndata: '+JSON.stringify(data)+'\n\n'});
    return;
  }
  await route.fulfill({contentType:'application/json',body:JSON.stringify(ok(data))});
 });
});
test('suggestion fills composer and response HTML is sanitized',async({page})=>{
 await page.goto('/');
 await page.getByRole('button',{name:/排查网络与 VPN/}).click();
 await expect(page.getByRole('textbox',{name:'输入问题'})).toHaveValue('VPN连接不上，应该怎么排查？');
 await page.getByRole('button',{name:'发送问题'}).click();
 await expect(page.locator('.message.assistant')).toContainText('安全回答');
 await expect(page.locator('.markdown [onerror]')).toHaveCount(0);
 expect(await page.evaluate(()=>('__xss' in window))).toBe(false);
});
test('request failure is visible and input becomes usable',async({page})=>{
 await page.route('**/api/chat/conversations/*/ask/stream',route=>route.fulfill({status:502,contentType:'application/json',body:JSON.stringify({success:false,errorMsg:'模型服务暂时不可用',data:null})}));
 await page.goto('/');
 await page.getByRole('textbox',{name:'输入问题'}).fill('你好');
 await page.getByRole('button',{name:'发送问题'}).click();
 await expect(page.getByRole('alert')).toContainText('模型服务暂时不可用');
 await expect(page.getByRole('textbox',{name:'输入问题'})).toBeEnabled();
});
test('mobile menu opens without horizontal overflow',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.goto('/');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.getByRole('button',{name:'打开导航'}).click();
 await page.getByRole('button',{name:'工具服务',exact:true}).click();
 await expect(page.getByRole('heading',{name:'把查询，交给合适的工具。'})).toBeVisible();
});

test('thinking and answer appear before done, and remain separate',async({page},info)=>{
 await page.goto('/');
 await page.evaluate(()=>{
   const original=window.fetch.bind(window);
   window.fetch=async(input,init)=>{
     if(String(input).endsWith('/ask/stream')){
       (window as any).__request=JSON.parse(String(init?.body));
       const stream=new ReadableStream<Uint8Array>({start(controller){
         (window as any).__push=(event:string,data:unknown)=>controller.enqueue(new TextEncoder().encode('event: '+event+'\ndata: '+JSON.stringify(data)+'\n\n'));
         (window as any).__close=()=>controller.close();
         init?.signal?.addEventListener('abort',()=>controller.error(new DOMException('Aborted','AbortError')));
       }});
       return new Response(stream,{headers:{'Content-Type':'text/event-stream'}});
     }
     return original(input,init);
   };
 });
 await page.getByRole('button',{name:'深度思考',exact:true}).click();
 await page.getByRole('textbox',{name:'输入问题'}).fill('如何排查 VPN？');
 await page.getByRole('button',{name:'发送问题'}).click();
 await expect.poll(()=>page.evaluate(()=>!!(window as any).__push)).toBe(true);
 expect(await page.evaluate(()=>(window as any).__request.deepThinking)).toBe(true);
 await page.evaluate(()=>(window as any).__push('reasoning',{text:'先核对现象，再结合已有资料分析。'}));
 await expect(page.locator('.reasoning-text')).toContainText('先核对现象');
 await expect(page.locator('.markdown')).toHaveCount(0);
 await page.evaluate(()=>(window as any).__push('delta',{text:'先检查网络连接。'}));
 await expect(page.locator('.markdown')).toContainText('先检查网络连接');
 await expect(page.getByRole('button',{name:'停止生成'})).toBeVisible();
 await expect(page.locator('.result-button')).toHaveCount(0);
 await page.screenshot({path:info.outputPath('thinking-stream-desktop.png')});
 await page.setViewportSize({width:390,height:844});
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.screenshot({path:info.outputPath('thinking-stream-mobile.png')});
 await page.evaluate(()=>{
   (window as any).__push('done',{answer:'先检查网络连接。',plan:{status:'matched',originalQuestion:'如何排查 VPN？',rewrittenQuestion:'如何排查 VPN？',tasks:[]},sources:[],branches:[]});
   (window as any).__close();
 });
 await expect(page.locator('.result-button')).toBeVisible();
 await expect(page.locator('.markdown')).toHaveText('先检查网络连接。');
 await page.getByRole('button',{name:'思考过程',exact:false}).click();
 await expect(page.locator('.reasoning-text')).toHaveCount(0);
});

test('stop aborts the reader and marks the partial reply incomplete',async({page})=>{
 await page.goto('/');
 await page.evaluate(()=>{
   const original=window.fetch.bind(window);
   window.fetch=async(input,init)=>{
     if(String(input).endsWith('/ask/stream')){
       const stream=new ReadableStream<Uint8Array>({start(c){
         c.enqueue(new TextEncoder().encode('event: delta\ndata: {"text":"部分答案"}\n\n'));
         init?.signal?.addEventListener('abort',()=>{(window as any).__aborted=true;c.error(new DOMException('Aborted','AbortError'));});
       }});
       return new Response(stream,{headers:{'Content-Type':'text/event-stream'}});
     }
     return original(input,init);
   };
 });
 await page.getByRole('textbox',{name:'输入问题'}).fill('你好');
 await page.getByRole('button',{name:'发送问题'}).click();
 await expect(page.locator('.markdown')).toContainText('部分答案');
 await page.getByRole('button',{name:'停止生成'}).click();
 await expect(page.getByRole('alert')).toContainText('已停止接收');
 await expect(page.locator('.interrupted-note')).toBeVisible();
 await expect(page.locator('.result-button')).toHaveCount(0);
 await expect(page.getByRole('textbox',{name:'输入问题'})).toBeEnabled();
 expect(await page.evaluate(()=>(window as any).__aborted)).toBe(true);
});

test('recent conversations scroll with a transparent track',async({page},info)=>{
 await page.setViewportSize({width:1280,height:720});await page.goto('/');
 const list=page.locator('.session-list');
 await expect(list.locator('button')).toHaveCount(30);
 await page.locator('h1').hover();
 expect(await list.evaluate(el=>getComputedStyle(el).scrollbarColor)).toBe('rgba(0, 0, 0, 0) rgba(0, 0, 0, 0)');
 await list.hover();
 expect(await list.evaluate(el=>getComputedStyle(el).scrollbarColor)).toContain('185, 204, 214');
 await list.evaluate(el=>el.scrollTop=el.scrollHeight);
 expect(await list.evaluate(el=>el.scrollTop)).toBeGreaterThan(0);
 await page.screenshot({path:info.outputPath('recent-conversations-hover.png')});
});
