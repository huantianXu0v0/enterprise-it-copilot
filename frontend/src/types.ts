export interface KnowledgeBase{id:string;name:string;collectionName:string;embeddingModel:string}
export interface DocumentItem{id:string;docName:string;fileType:string;fileSize:number;status:string;chunkCount:number}
export interface Session{conversationId:string;title:string;lastTime?:string}
export interface Tool{name:string;description:string;inputSchema:{properties?:Record<string,{type?:string;description?:string;default?:unknown}>;required?:string[]};annotations?:{readOnlyHint?:boolean}}
export interface Intent{intentCode:string;name:string;description?:string;level:number;kind:number;kbId?:string;mcpToolId?:string;executable:boolean;children:Intent[];examples?:string[]}
export interface Task{intentCode:string;type:string;kbId?:string;mcpToolId?:string}
export interface Source{chunkId:string;docName:string;content:string;score?:number}
export interface Answer{answer:string;plan:{status:string;originalQuestion:string;rewrittenQuestion:string;tasks:Task[]};sources:Source[];branches:{task:Task;status:string;message:string}[]}
export interface Message{id:string;role:string;content:string;createTime?:string;result?:Answer;reasoning?:string;thinkingRequested?:boolean;
  thinkingState?:'waiting'|'thinking'|'finished'|'unavailable';reasoningOpen?:boolean;
  streaming?:boolean;interrupted?:boolean}
