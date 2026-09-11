// Local protocol oracle, pinned to openclaw/openclaw v2026.9.4. Never use real credentials.
import http from 'node:http';
import { createHash, createPublicKey, randomBytes, verify } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const STUB_TOKEN = 'stub-shared-token';
const MAX_FRAME = 25 * 1024 * 1024;
const COMMANDS = ['camera.snap', 'camera.list', 'device.status', 'device.info'];
const normalize = value => (value ?? '').trim().replace(/[A-Z]/g, c => c.toLowerCase());

// Protocol 4 retains the v3 signature format; scopes and token selection are auth-bound.
export function signaturePayload(p) {
  return ['v3', p.device.id, p.client.id, p.client.mode, p.role, p.scopes.join(','),
    String(p.device.signedAt), p.auth?.token ?? p.auth?.deviceToken ?? '', p.device.nonce,
    normalize(p.client.platform), normalize(p.client.deviceFamily)].join('|');
}

export function validateConnect(p, { protocol = 4, nonce, tokens, now = Date.now() }) {
  if (!Number.isInteger(p?.minProtocol) || !Number.isInteger(p?.maxProtocol) ||
      p.minProtocol > protocol || p.maxProtocol < protocol) throw Error('PROTOCOL_MISMATCH');
  if (p.client?.id !== 'openclaw-android' || p.client.mode !== 'node' ||
      p.role !== 'node' || !Array.isArray(p.scopes) || p.scopes.length) throw Error('ROLE_OR_SCOPE');
  if (!Array.isArray(p.commands) || p.commands.some(c => !COMMANDS.includes(c)) ||
      !Array.isArray(p.caps) || p.caps.some(c => c !== 'camera')) throw Error('CAPABILITIES');
  if (!nonce || p.device?.nonce !== nonce || !Number.isInteger(p.device.signedAt) ||
      Math.abs(now - p.device.signedAt) > 120000) throw Error('DEVICE_PROOF');
  const raw = Buffer.from(p.device.publicKey, 'base64url');
  if (raw.length !== 32 || createHash('sha256').update(raw).digest('hex') !== p.device.id) throw Error('DEVICE_ID');
  const publicKey = createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw]), format: 'der', type: 'spki' });
  if (!verify(null, Buffer.from(signaturePayload(p)), publicKey, Buffer.from(p.device.signature, 'base64url'))) throw Error('SIGNATURE');
  const auth = p.auth ?? {};
  if (auth.deviceToken) {
    if (auth.token || tokens.get(p.device.id) !== auth.deviceToken) throw Error('AUTH_DEVICE_TOKEN_MISMATCH');
  } else if (auth.token !== STUB_TOKEN) throw Error('AUTH_TOKEN_MISMATCH');
}

function frame(opcode, data) {
  const payload = Buffer.isBuffer(data) ? data : Buffer.from(data);
  const header = Buffer.alloc(payload.length < 126 ? 2 : payload.length <= 65535 ? 4 : 10);
  header[0] = 0x80 | opcode;
  header[1] = header.length === 2 ? payload.length : header.length === 4 ? 126 : 127;
  if (header.length === 4) header.writeUInt16BE(payload.length, 2);
  if (header.length === 10) header.writeBigUInt64BE(BigInt(payload.length), 2);
  return Buffer.concat([header, payload]);
}

// Bounded local WebSocket transport. Supports masked text/continuations, close, and ping/pong.
function readFrames(socket, onText) {
  let buffer = Buffer.alloc(0), fragments = [], fragmentBytes = 0, fragmenting = false;
  return chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    try {
      while (buffer.length >= 2) {
        const fin = !!(buffer[0] & 0x80), opcode = buffer[0] & 15;
        if ((buffer[0] & 0x70) || !(buffer[1] & 0x80)) throw Error('invalid frame');
        let size = buffer[1] & 127, offset = 2;
        if (size === 126) { if (buffer.length < 4) return; size = buffer.readUInt16BE(2); offset = 4; }
        if (size === 127) {
          if (buffer.length < 10) return;
          const bigSize = buffer.readBigUInt64BE(2);
          if (bigSize > BigInt(MAX_FRAME)) throw Error('oversize frame');
          size = Number(bigSize); offset = 10;
        }
        if (size > MAX_FRAME || (opcode >= 8 && (!fin || size > 125))) throw Error('invalid size');
        if (buffer.length < offset + 4 + size) return;
        const mask = buffer.subarray(offset, offset + 4);
        const body = Buffer.from(buffer.subarray(offset + 4, offset + 4 + size));
        for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
        buffer = buffer.subarray(offset + 4 + size);
        if (opcode === 8) { socket.end(frame(8, body)); return; }
        if (opcode === 9) { socket.write(frame(10, body)); continue; }
        if (opcode === 10) continue;
        if ((opcode === 1 && fragmenting) || (opcode === 0 && !fragmenting) || ![0, 1].includes(opcode)) throw Error('invalid opcode');
        fragmentBytes += size;
        if (fragmentBytes > MAX_FRAME) throw Error('oversize message');
        fragments.push(body); fragmenting = !fin;
        if (fin) {
          const text = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(fragments));
          fragments = []; fragmentBytes = 0;
          onText(text);
        }
      }
    } catch { socket.destroy(); }
  };
}

export function createStub({ protocol = 4, pairingOnce = false, command = 'device.status', onResult = () => {} } = {}) {
  if (![3, 4].includes(protocol) || !COMMANDS.includes(command)) throw Error('Unsupported fixture configuration');
  const tokens = new Map(), pending = new Set(), sockets = new Set();
  const server = http.createServer((req, res) => { res.writeHead(404); res.end(); });
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/' || req.headers['sec-websocket-version'] !== '13' || !req.headers['sec-websocket-key']) {
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); return;
    }
    const accept = createHash('sha1').update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    sockets.add(socket);
    const send = object => socket.write(frame(1, JSON.stringify(object)));
    const nonce = randomBytes(16).toString('base64url');
    let nodeId, tick, chatSession;
    const timeout = setTimeout(() => socket.destroy(), 10000);
    const read = readFrames(socket, text => {
      let request;
      try {
        request = JSON.parse(text);
        if (request.type !== 'req') throw Error('INVALID_REQUEST');
        if (!nodeId) {
          if (request.method !== 'connect') throw Error('CONNECT_REQUIRED');
          validateConnect(request.params, { protocol, nonce, tokens });
          const id = request.params.device.id;
          if (pairingOnce && !pending.has(id)) {
            pending.add(id);
            send({ type: 'res', id: request.id, ok: false, error: { code: 'NOT_PAIRED', message: 'fixture: reconnect to simulate approval', details: { code: 'PAIRING_REQUIRED', requestId: 'stub-pairing' } } });
            socket.end(frame(8, Buffer.from([3, 240]))); return;
          }
          nodeId = id;
          clearTimeout(timeout);
          const token = tokens.get(id) ?? randomBytes(24).toString('base64url');
          tokens.set(id, token);
          send({ type: 'res', id: request.id, ok: true, payload: {
            type: 'hello-ok', protocol, server: { version: 'stub-v2026.9.4', connId: nonce },
            features: { methods: ['node.invoke.result', 'node.event'], events: ['node.invoke.request', 'tick', 'chat'] },
            snapshot: { presence: [], health: {}, stateVersion: { presence: 0, health: 0 }, uptimeMs: 0 },
            auth: { role: 'node', scopes: [], deviceToken: token },
            policy: { maxPayload: MAX_FRAME, maxBufferedBytes: MAX_FRAME * 2, tickIntervalMs: 30000 },
          } });
          send({ type: 'event', event: 'node.invoke.request', payload: {
            id: 'stub-invoke', nodeId, command, paramsJSON: '{"maxWidth":640,"quality":0.8}', timeoutMs: 30000,
          } });
          tick = setInterval(() => send({ type: 'event', event: 'tick', payload: { ts: Date.now() } }), 30000);
        } else {
          const p = request.params;
          if (request.method === 'node.event') {
            const event = JSON.parse(p.payloadJSON);
            if (p.event === 'chat.subscribe') {
              if (typeof event.sessionKey !== 'string' || !event.sessionKey) throw Error('SESSION_REQUIRED');
              chatSession = event.sessionKey;
              send({ type: 'res', id: request.id, ok: true, payload: { ok: true } });
            } else if (p.event === 'chat.unsubscribe') {
              if (typeof event.sessionKey !== 'string' || !event.sessionKey) throw Error('SESSION_REQUIRED');
              if (event.sessionKey === chatSession) chatSession = null;
              send({ type: 'res', id: request.id, ok: true, payload: { ok: true } });
            } else if (p.event === 'agent.request') {
              if (event.sessionKey !== chatSession || event.deliver !== false || event.receipt !== false ||
                  typeof event.message !== 'string' || event.message.length > 20000) throw Error('INVALID_AGENT_REQUEST');
              send({ type: 'res', id: request.id, ok: true, payload: { ok: true } });
              const base = { runId: randomBytes(8).toString('hex'), sessionKey: chatSession };
              send({ type: 'event', event: 'chat', payload: { ...base, seq: 1, state: 'delta', deltaText: 'Stub ' } });
              send({ type: 'event', event: 'chat', payload: { ...base, seq: 2, state: 'delta', deltaText: 'reply' } });
              send({ type: 'event', event: 'chat', payload: { ...base, seq: 3, state: 'final', message: { role: 'assistant', content: [{ type: 'text', text: 'Stub reply' }] } } });
            } else throw Error('UNSUPPORTED_NODE_EVENT');
            return;
          }
          if (request.method !== 'node.invoke.result') throw Error('NODE_ROLE_ONLY');
          if (p?.id !== 'stub-invoke' || p.nodeId !== nodeId || typeof p.ok !== 'boolean' ||
              Object.keys(p).some(k => !['id', 'nodeId', 'ok', 'payload', 'payloadJSON', 'error'].includes(k))) throw Error('INVALID_RESULT');
          if (p.payloadJSON !== undefined) JSON.parse(p.payloadJSON);
          onResult(p);
          send({ type: 'res', id: request.id, ok: true, payload: { ok: true } });
        }
      } catch (error) {
        send({ type: 'res', id: request?.id ?? 'invalid', ok: false, error: { code: 'INVALID_REQUEST', message: error.message } });
        socket.end(frame(8, Buffer.from([3, 240])));
      }
    });
    socket.on('data', read);
    socket.on('error', () => {});
    socket.on('close', () => { clearTimeout(timeout); clearInterval(tick); sockets.delete(socket); });
    send({ type: 'event', event: 'connect.challenge', payload: { nonce, ts: Date.now() } });
    if (head.length) read(head);
  });
  return {
    server,
    async close() { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const protocol = process.argv.includes('--protocol3') ? 3 : 4;
  const command = process.argv.includes('--camera') ? 'camera.snap' : 'device.status';
  const stub = createStub({ protocol, command, pairingOnce: process.argv.includes('--pairing-once'),
    onResult: result => console.log(`invoke ${result.id}: ${result.ok ? 'ok' : 'failed'} (payload omitted)`),
  });
  stub.server.listen(18789, '127.0.0.1', () => console.log(`Loopback fixture ws://127.0.0.1:18789/ protocol ${protocol}; use token ${STUB_TOKEN}`));
}
