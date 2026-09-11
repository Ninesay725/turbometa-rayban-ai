// Minimal OpenClaw Gateway stand-in for the manual checklist (Task 9).
// Usage: node stub.js [--port 18789] [--token T] [--not-paired]
//   a + Enter  -> approve (stop answering NOT_PAIRED; the app reconnects on its own)
//   s + Enter  -> send a node.invoke camera.snap now (also sent automatically every 20 s)
import { WebSocketServer } from "ws";
import { randomUUID } from "node:crypto";
import { writeFileSync } from "node:fs";

const args = process.argv.slice(2);
const flag = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const port = Number(flag("--port") ?? 18789);
const expectedToken = flag("--token");
let notPaired = args.includes("--not-paired");

const wss = new WebSocketServer({ host: "0.0.0.0", port });
console.log(`stub gateway listening on ws://0.0.0.0:${port}` + (notPaired ? " (answering NOT_PAIRED until you press a)" : ""));

const snapRequest = (asReq) => asReq
  ? { type: "req", id: `snap-${Date.now()}`, method: "node.invoke",
      params: { command: "camera.snap", params: { maxWidth: 640, quality: 0.7, format: "jpg" }, timeoutMs: 30000 } }
  : { type: "event", event: "node.invoke.request", payload: null,
      params: { id: `inv-${Date.now()}`, command: "camera.snap", params: { maxWidth: 640, quality: 0.7, format: "jpg" }, timeoutMs: 30000 } };

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://placeholder");
  const token = url.searchParams.get("token");
  console.log(`client connected from ${req.socket.remoteAddress}, token=${token ?? "(none)"}`);
  if (expectedToken && token !== expectedToken) { console.log("bad token -> closing 1008"); ws.close(1008, "bad token"); return; }
  const send = (obj) => ws.send(JSON.stringify(obj));
  send({ type: "event", event: "connect.challenge", payload: { nonce: randomUUID() } });

  let snapTimer;
  ws.on("message", (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.type === "req" && msg.method === "connect") {
      const p = msg.params;
      console.log(`connect: client=${p.client.id}/${p.client.platform} v${p.client.version} model=${p.client.modelIdentifier} device=${p.device.id.slice(0, 8)}… nonce=${p.device.nonce} commands=${p.commands.join(",")}`);
      if (notPaired) { send({ type: "res", id: msg.id, ok: false, error: { code: "NOT_PAIRED", message: "device not paired" } }); return; }
      send({ type: "res", id: msg.id, ok: true, payload: { protocol: 3 } });
      snapTimer = setInterval(() => { console.log("-> node.invoke.request camera.snap"); send(snapRequest(false)); }, 20000);
      return;
    }
    if (msg.type === "req" && msg.method === "chat.send") {
      send({ type: "res", id: msg.id, ok: true, payload: {} });
      const text = msg.params.message;
      const hasImage = (msg.params.attachments ?? []).length > 0;
      console.log(`chat.send [${msg.params.sessionKey}]: "${text}"${hasImage ? " + image/jpeg attachment" : ""}`);
      const reply = `echo: ${text}${hasImage ? " (I received a JPEG)" : ""}`;
      let shown = 0;
      const t = setInterval(() => {
        shown += 5;
        if (shown < reply.length) {
          send({ type: "event", event: "chat", payload: { state: "delta", message: { role: "assistant", content: [{ type: "text", text: reply.slice(0, shown) }] } } });
        } else {
          clearInterval(t);
          send({ type: "event", event: "chat", payload: { state: "final", message: { role: "assistant", content: [{ type: "text", text: reply }] } } });
        }
      }, 150);
      return;
    }
    if (msg.type === "req" && msg.method === "node.invoke.result") {
      send({ type: "res", id: msg.id, ok: true, payload: {} });
      const p = msg.params;
      if (p.ok && p.payloadjson) {
        const payload = JSON.parse(p.payloadjson);
        if (payload.base64) {
          writeFileSync("last-snap.jpg", Buffer.from(payload.base64, "base64"));
          console.log(`<- ${p.id} from ${p.nodeId}: ${payload.width}x${payload.height} ${payload.format}, saved last-snap.jpg`);
        } else {
          console.log(`<- ${p.id} from ${p.nodeId}: ${p.payloadjson}`);
        }
      } else {
        console.log(`<- ${p.id} from ${p.nodeId}: error ${p.error?.code} — ${p.error?.message}`);
      }
      return;
    }
    if (msg.type === "req" && msg.method === "tick") { send({ type: "res", id: msg.id, ok: true, payload: {} }); return; }
    console.log("unhandled:", JSON.stringify(msg).slice(0, 200));
  });
  ws.on("close", (code, reason) => { clearInterval(snapTimer); console.log(`client disconnected ${code} ${reason}`); });
});

process.stdin.on("data", (d) => {
  const key = d.toString().trim();
  if (key === "a") { notPaired = false; console.log("approved: the next connect gets ok:true"); }
  if (key === "s") for (const c of wss.clients) c.send(JSON.stringify(snapRequest(true)));
});
