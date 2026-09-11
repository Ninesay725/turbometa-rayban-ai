// Local-only, deterministic UI smoke fixture. It never logs requests or uses a real model/key.
// node custom-ai-smoke-server.js [port]; adb reverse tcp:18791 tcp:18791
const http = require('node:http');
const port = Number(process.argv[2] || 18791);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid test port');
const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') { res.writeHead(404).end(); return; }
  let raw = ''; let oversized = false;
  req.on('data', chunk => { raw += chunk; if (raw.length > 4 * 1024 * 1024) { oversized = true; req.destroy(); } });
  req.on('end', () => {
    if (oversized) return;
    try {
      const body = JSON.parse(raw);
      const messages = Array.isArray(body.messages) ? body.messages : [];
      const tools = new Set((body.tools || []).map(t => t.function?.name));
      const last = messages.at(-1);
      const toolResults = messages.filter(m => m.role === 'tool');
      let message = {role: 'assistant', content: '本地测试通过：这是模拟模型的中文回答。真实模型、翻译质量与眼镜音频仍需另行验证。'};
      if (toolResults.length) {
        message.content = toolResults.some(t => String(t.content).includes('error')) ? '本地测试：应用报告操作不可用，未声称执行成功。' : '本地测试：已收到工具执行结果。';
      } else if (typeof last?.content === 'string' && /拍照|camera/i.test(last.content) && tools.has('camera_capture')) {
        message = {role: 'assistant', content: null, tool_calls: [{id: 'fixture-camera', type: 'function', function: {name: 'camera_capture', arguments: '{}'}}]};
      } else if (typeof last?.content === 'string' && /暂停|pause/i.test(last.content) && tools.has('music_control')) {
        message = {role: 'assistant', content: null, tool_calls: [{id: 'fixture-music', type: 'function', function: {name: 'music_control', arguments: '{"action":"pause"}'}}]};
      }
      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({choices: [{message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop'}]}));
    } catch { res.writeHead(400).end(); }
  });
});
server.listen(port, '127.0.0.1', () => process.stdout.write(`Local assistant fixture listening on 127.0.0.1:${port}\n`));
process.on('SIGINT', () => server.close(() => process.exit(0)));
