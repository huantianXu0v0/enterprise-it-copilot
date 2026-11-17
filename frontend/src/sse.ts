import type {Answer} from './types';

export interface StreamEvent { event: string; data: string }

// 按 SSE 的空行边界拆事件，而不是把一次网络 read 当成一次事件。
// TextDecoder 保留跨网络包的中文字符，支持 CRLF、多行 data 和心跳注释。
export async function readSse(
  stream: ReadableStream<Uint8Array>,
  onEvent: (event: StreamEvent) => boolean | void,
  signal?: AbortSignal
): Promise<void> {
  const reader = stream.getReader(), decoder = new TextDecoder();
  let pending = '', event = 'message', data: string[] = [];
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal?.addEventListener('abort', abort, {once: true});
  try {
    while (true) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const {value, done} = await reader.read();
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      pending += done ? decoder.decode() : decoder.decode(value, {stream: true});
      let end: number;
      while ((end = pending.indexOf('\n')) >= 0) {
        let line = pending.slice(0, end);
        pending = pending.slice(end + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        if (line === '') {
          if (data.length && onEvent({event, data: data.join('\n')}) === false) return;
          event = 'message'; data = [];
        } else if (!line.startsWith(':')) {
          const colon = line.indexOf(':');
          const field = colon < 0 ? line : line.slice(0, colon);
          let value = colon < 0 ? '' : line.slice(colon + 1);
          if (value.startsWith(' ')) value = value.slice(1);
          if (field === 'event') event = value || 'message';
          if (field === 'data') data.push(value);
        }
      }
      if (done) return; // 未结束的事件不派发，由调用方检查 done 事件。
    }
  } finally {
    signal?.removeEventListener('abort', abort);
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export async function streamAsk(
  conversationId: string,
  body: {question: string; topK: number; minScore: number; deepThinking: boolean},
  signal: AbortSignal,
  onEvent: (event: 'phase' | 'reasoning' | 'delta', text: string) => void
): Promise<Answer> {
  const response = await fetch('/api/chat/conversations/' + encodeURIComponent(conversationId) + '/ask/stream', {
    method: 'POST', signal,
    headers: {'Content-Type': 'application/json', Accept: 'text/event-stream'},
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new Error(payload?.errorMsg || '请求未完成（' + response.status + '）');
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    throw new Error('服务没有返回流式数据，请确认后端已更新并重启。');
  }
  let result: Answer | undefined;
  await readSse(response.body, ({event, data}) => {
    if (!['phase', 'reasoning', 'delta', 'done', 'error'].includes(event)) return;
    let payload;
    try { payload = JSON.parse(data); }
    catch { throw new Error('收到无法解析的流式数据，请刷新会话确认结果。'); }
    if (event === 'error') throw new Error(payload.message || '回答未完成，请稍后重试。');
    if (event === 'done') {
      if (typeof payload.answer !== 'string' || !payload.plan
          || !Array.isArray(payload.sources) || !Array.isArray(payload.branches)) {
        throw new Error('回答完成事件不完整，请刷新会话确认结果。');
      }
      result = payload as Answer;
      return false;
    }
    if (event === 'phase' && typeof payload.message === 'string') onEvent('phase', payload.message);
    if ((event === 'delta' || event === 'reasoning') && typeof payload.text === 'string') onEvent(event, payload.text);
  }, signal);
  if (!result) throw new Error('连接提前结束，回答可能不完整；请刷新会话确认是否已保存。');
  return result;
}
