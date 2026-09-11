import test from 'node:test';
import assert from 'node:assert/strict';
import { createPrivateKey, createPublicKey, createHash, sign } from 'node:crypto';
import { createStub, signaturePayload, STUB_TOKEN, validateConnect } from './gateway.mjs';

const key = createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), Buffer.alloc(32, 7)]), format: 'der', type: 'pkcs8' });
const publicKey = createPublicKey(key).export({ format: 'der', type: 'spki' }).subarray(-32);
const deviceId = createHash('sha256').update(publicKey).digest('hex');
function connectParams(nonce, auth = { token: STUB_TOKEN }) {
  const p = { minProtocol: 4, maxProtocol: 4, client: { id: 'openclaw-android', mode: 'node', platform: 'android', version: 'test' },
    role: 'node', scopes: [], caps: ['camera'], commands: ['camera.snap', 'camera.list', 'device.status', 'device.info'], auth,
    device: { id: deviceId, publicKey: publicKey.toString('base64url'), signedAt: Date.now(), nonce },
  };
  return resign(p);
}
function resign(p) { p.device.signature = sign(null, Buffer.from(signaturePayload(p)), key).toString('base64url'); return p; }

test('released protocol identity, nonce, signature, role and credentials are independently checked', () => {
  const options = { nonce: 'challenge', tokens: new Map([[deviceId, 'node-only']]) };
  assert.doesNotThrow(() => validateConnect(connectParams('challenge'), options));
  assert.doesNotThrow(() => validateConnect(connectParams('challenge', { deviceToken: 'node-only' }), options));
  for (const mutate of [
    p => { p.role = 'operator'; }, p => { p.scopes = ['operator.admin']; },
    p => { p.client.id = 'android-node'; }, p => { p.maxProtocol = 3; },
    p => { p.device.nonce = 'replayed'; }, p => { p.device.signedAt -= 121000; },
    p => { p.device.id = 'other'; }, p => { p.device.signature = 'bad'; },
    p => { p.auth.deviceToken = 'wrong'; }, p => { p.commands.push('system.run'); },
  ]) {
    const p = connectParams('challenge'); mutate(p);
    assert.throws(() => validateConnect(p, options));
  }
  const p = connectParams('challenge'); p.client.deviceFamily = 'Pixel 5!';
  assert.throws(() => validateConnect(p, options), /SIGNATURE/);
  assert.doesNotThrow(() => validateConnect(resign(p), options));
});

test('protocol 3 is an explicit node range, with identical signature and role', () => {
  const p = connectParams('challenge'); p.minProtocol = 3;
  assert.doesNotThrow(() => validateConnect(p, { protocol: 3, nonce: 'challenge', tokens: new Map() }));
});

test('loopback handshake, pairing close, canonical invoke and paired-token reconnect', { timeout: 10000 }, async () => {
  const results = [];
  const stub = createStub({ pairingOnce: true, onResult: p => results.push(p) });
  await new Promise(resolve => stub.server.listen(0, '127.0.0.1', resolve));
  const url = `ws://127.0.0.1:${stub.server.address().port}/`;
  const exchange = auth => new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    let token, paired = false;
    socket.onerror = reject;
    socket.onmessage = event => {
      try {
        const message = JSON.parse(event.data);
        if (message.event === 'connect.challenge') {
          socket.send(JSON.stringify({ type: 'req', id: 'connect', method: 'connect', params: connectParams(message.payload.nonce, auth) }));
        } else if (message.id === 'connect') {
          if (!message.ok) { assert.equal(message.error.code, 'NOT_PAIRED'); paired = true; return; }
          assert.equal(message.payload.auth.role, 'node');
          token = message.payload.auth.deviceToken;
        } else if (message.event === 'node.invoke.request') {
          assert.equal(message.payload.nodeId, deviceId);
          assert.deepEqual(JSON.parse(message.payload.paramsJSON), { maxWidth: 640, quality: 0.8 });
          socket.send(JSON.stringify({ type: 'req', id: 'result', method: 'node.invoke.result', params: {
            id: message.payload.id, nodeId: deviceId, ok: true, payloadJSON: '{"deviceConnected":true}',
          } }));
        } else if (message.id === 'result') {
          assert.equal(message.ok, true); socket.close();
        }
      } catch (error) { socket.close(); reject(error); }
    };
    socket.onclose = () => resolve({ token, paired });
  });
  try {
    assert.equal((await exchange()).paired, true);
    const hello = await exchange();
    assert.ok(hello.token);
    assert.equal((await exchange({ deviceToken: hello.token })).token, hello.token);
    assert.equal(results.length, 2);
  } finally { await stub.close(); }
});

test('node-role chat receives finals and switches subscriptions without operator scopes', { timeout: 10000 }, async () => {
  const stub = createStub();
  await new Promise(resolve => stub.server.listen(0, '127.0.0.1', resolve));
  try {
    const events = await new Promise((resolve, reject) => {
      const socket = new WebSocket(`ws://127.0.0.1:${stub.server.address().port}/`);
      const replies = [];
      socket.onerror = reject;
      const sendEvent = (id, event, payload) => socket.send(JSON.stringify({ type: 'req', id, method: 'node.event', params: { event, payloadJSON: JSON.stringify(payload) } }));
      socket.onmessage = ({ data }) => {
        try {
          const p = JSON.parse(data);
          if (p.event === 'connect.challenge') socket.send(JSON.stringify({ type: 'req', id: 'connect', method: 'connect', params: connectParams(p.payload.nonce) }));
          else if (p.id === 'connect') {
            assert.deepEqual(p.payload.auth.scopes, []);
            sendEvent('subscribe', 'chat.subscribe', { sessionKey: 'agent:main:turbometa-chat' });
          } else if (p.id === 'subscribe') {
            assert.equal(p.ok, true);
            sendEvent('turn', 'agent.request', { sessionKey: 'agent:main:turbometa-chat', message: 'hello', deliver: false, receipt: false });
          } else if (p.id === 'unsubscribe') {
            assert.equal(p.ok, true);
            sendEvent('subscribe-new', 'chat.subscribe', { sessionKey: 'agent:main:turbometa-chat-new' });
          } else if (p.id === 'subscribe-new') {
            assert.equal(p.ok, true);
            sendEvent('turn-new', 'agent.request', { sessionKey: 'agent:main:turbometa-chat-new', message: 'hello again', deliver: false, receipt: false });
          } else if (p.event === 'chat') {
            replies.push(p.payload);
            if (p.payload.state === 'final') {
              if (p.payload.sessionKey === 'agent:main:turbometa-chat') sendEvent('unsubscribe', 'chat.unsubscribe', { sessionKey: p.payload.sessionKey });
              else { socket.close(); resolve(replies); }
            }
          }
        } catch (error) { socket.close(); reject(error); }
      };
    });
    assert.deepEqual(events.map(e => e.state), ['delta', 'delta', 'final', 'delta', 'delta', 'final']);
    assert.deepEqual(events.filter(e => e.state === 'final').map(e => e.sessionKey), ['agent:main:turbometa-chat', 'agent:main:turbometa-chat-new']);
    assert.equal(events.at(-1).message.content[0].text, 'Stub reply');
  } finally { await stub.close(); }
});
