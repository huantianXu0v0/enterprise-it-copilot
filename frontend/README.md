# 序知 · IT 工作台

Vue 3 + TypeScript + Vite，连接真实后端接口。

## 启动

1. 启动 MCP 工具服务，默认端口1002。
2. 在 IDEA 中按原运行配置启动 EnterpriseItCopilotApplication，默认端口1001。保留 chat-key、embed-key 等原有环境变量。
3. 在 frontend 目录执行 npm install，再执行 npm run dev。
4. 打开 http://127.0.0.1:5173。

本项目锁定的Vite 6支持当前Node 20.15。浏览器只请求 /api，由开发代理转发到后端，不保存模型密钥。
如后端地址不同，在PowerShell设置 $env:BACKEND_URL="http://127.0.0.1:你的端口"，再启动前端。

## 功能

智能问答：SSE 流式回答、可选深度思考与可折叠思考框、停止生成、历史会话、消息分页、Markdown、复制、当前回答的引用与执行结果。
知识空间：创建知识库、文档列表、上传并索引、更新索引和分块预览。
意图编排：搜索筛选、编辑资源绑定、清缓存、幂等补齐IT意图。
工具服务：实际工具列表、参数定义、三个只读示例工具的手动测试。

聊天使用 POST /chat/conversations/{conversationId}/ask/stream。
正文和思考内容分别由 delta、reasoning 事件增量显示；done 表示回答已保存。原同步 ask 接口仍保留。
深度思考默认关闭，打开后仅最终回答请求携带 enable_thinking=true；问题改写、意图识别、参数提取和摘要保持关闭。
当前适配 DashScope Qwen 的 reasoning_content；模型没有返回思考片段时会明确显示，没有模拟思考文本。
思考内容仅保留在本次页面会话中，不写入历史数据库或摘要，刷新历史只显示最终回答。
停止生成会中断浏览器连接；服务端通过写入失败/心跳发现断开并中断任务，最长可能需要等待约15秒的下一次心跳。
未完整生成的问答不保存；若在保存完成附近断开，页面提示刷新确认，避免误判保存结果。
最近对话列表支持滚动，滚动条平时透明、悬停或键盘聚焦时显示。
历史表仅保存问答正文，引用与执行详情来自当前请求，刷新历史后不会伪造旧引用。
当前沿用local用户，没有新增多用户登录与授权体系。

## 验证

npm run build：类型检查和生产构建。
npm run test:e2e：浏览器与 SSE 解析回归测试，需要先启动前端；使用模拟接口，不调用真实模型。
node tests/live-smoke.mjs：真实服务联调，会创建测试对话并调用已配置的模型与只读工具。
首次运行浏览器测试前执行 npx playwright install chromium。

生产构建在dist目录；部署时请将/api反向代理到后端，参考nginx.example.conf。

## 代码入口

src/App.vue：整体布局、导航、服务状态。
src/views/ChatView.vue：对话和回答依据。
src/views/KnowledgeView.vue：知识与文档。
src/views/IntentView.vue：意图管理。
src/views/ToolsView.vue：工具发现与测试。
src/api.ts：普通 JSON 请求与错误提示。\nsrc/sse.ts：POST 流式请求、UTF-8 分包、SSE 事件解析、异常与中止。
src/style.css：桌面与手机样式。


## 提交 Git

frontend 源码、package.json、package-lock.json、测试和部署示例均可提交。
仅忽略 node_modules、dist、测试产物和本地 .env 配置；不要将模型密钥放入 VITE_ 环境变量。

## 本次后端入口

RagChatController.askStream → RouteChatStreamService → RouteExecutionService.streamTurn → ChatModelService。
请求示例：{"question":"如何排查VPN？","topK":5,"minScore":0.3,"deepThinking":true}。
phase：当前处理阶段；reasoning：{"text":"思考片段"}；delta：{"text":"正文片段"}；
done：与同步接口的 data 相同，包含 answer、plan、sources、branches；error：{"message":"错误说明"}。
没有匹配意图时流式自然兜底，plan.status 为 fallback；所有分支缺参/失败/无资料时直接返回明确说明，不额外调用模型。
可选后端配置 ai.chat.thinking-budget 默认2048，限制思考预算（1～8192）；模型连接仍有读取超时，整轮 SSE 超时180秒。
部署反向代理必须关闭响应缓冲；参见 nginx.example.conf。
