import test from 'node:test';
import assert from 'node:assert/strict';
import { annexBNalus, touchPoint, friendlyError, PhoneViewer } from './viewer.js';

test('mixed Annex-B start codes preserve SPS/PPS and discard empty delimiters', () => {
  const data = Uint8Array.from([0,0,0,1,0x67,0x42,0,0,1,0x68,0xce,0,0,1]);
  assert.deepEqual(annexBNalus(data).map(x => [...x]), [[0x67,0x42],[0x68,0xce]]);
});

test('touch coordinates scale to the video, including edges outside the canvas', () => {
  const rect = {left:100,top:50,width:360,height:800}, size = {w:1080,h:2400};
  assert.deepEqual(touchPoint(rect,size,280,450),{x:540,y:1200});
  assert.deepEqual(touchPoint(rect,size,99,900),{x:0,y:2399});
  assert.equal(touchPoint({...rect,width:0},size,0,0),null);
});

test('stream launch failures explain which step went wrong', () => {
  assert.equal(friendlyError('stream_root_denied'), '手机伙伴没拿到 Root：请在 Root 管理器里给「AI 手机伙伴」永久授权，再重新连接。');
  assert.equal(friendlyError('stream_server_failed'), '手机上的投屏组件（scrcpy）没能启动，这台手机的系统可能不兼容。');
  assert.equal(friendlyError('stream_connect_timeout'), '投屏组件已启动，但 10 秒内没连上手机伙伴，请重新连接。');
  assert.equal(friendlyError('stream_capture_failed'), '投屏中途出错了，请重新连接；反复出现请看教程里的常见问题。');
  assert.equal(friendlyError(''), '暂时没能接上手机。');
});

test('stop deletes a session whose creation response arrived after navigation', async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url,options) => { requests.push({url,options}); return {ok:true}; };
  try {
    const viewer = new PhoneViewer({canvas:{},base:'https://example.test/api/phone',token:'fixture-only-token',update(){},resized(){},failure(){}});
    let finishOpening;
    viewer.opening = new Promise(resolve => {finishOpening=resolve;});
    const stopped = viewer.stop();
    viewer.session = {id:'a'.repeat(32)};
    finishOpening();
    await stopped;
    assert.equal(requests.length,1);
    assert.equal(requests[0].options.method,'DELETE');
    assert.equal(requests[0].options.headers.Authorization,'Bearer fixture-only-token');
    assert.ok(!requests[0].url.includes('token'));
    assert.equal(viewer.token,'');
    await viewer.stop();
    assert.equal(requests.length,1);
  } finally {globalThis.fetch=originalFetch;}
});

test('gesture cancellation clears every finger before sending ACTION_CANCEL', () => {
  const viewer = new PhoneViewer({canvas:{},base:'',token:'',update(){},resized(){},failure(){}});
  viewer.fingers.set(1,{pointer_id:0,x:12,y:18,width:100,height:200});
  viewer.fingers.set(2,{pointer_id:1,x:20,y:25,width:100,height:200});
  const messages=[]; viewer.send = message => {assert.equal(viewer.fingers.size,0);messages.push(message);return true;};
  viewer.cancelTouches(); viewer.cancelTouches();
  assert.equal(messages.length,1);
  assert.equal(messages[0].action,3);
});

// Run the real viewer and app event handlers with an in-memory phone/browser.
// No server, phone, credentials, browser profile, or shared debugging port is used.
function browserFixture(t) {
  const requests = [], sockets = [], decoders = [], intervals = new Set();
  const context = {clearRect(){},drawImage(){}};
  class Element {
    constructor() { this.listeners = {}; this.dataset = {}; this.value = ''; this.style = {}; this.hidden = false; this.disabled = false; this.span = {textContent:''}; this.captured = new Set(); }
    addEventListener(name, callback) { (this.listeners[name] ||= []).push(callback); }
    async emit(name, values = {}) {
      const event = {preventDefault(){},target:this,...values};
      for (const callback of this.listeners[name] || []) await callback(event);
    }
    querySelector() { return this.span; }
    getContext() { return context; }
    getBoundingClientRect() { return {left:0,top:0,width:100,height:200}; }
    setPointerCapture(id) { this.captured.add(id); }
    hasPointerCapture(id) { return this.captured.has(id); }
    releasePointerCapture(id) { this.captured.delete(id); }
  }
  const elements = new Map();
  const element = id => { if (!elements.has(id)) elements.set(id,new Element()); return elements.get(id); };
  const keys = [4,3,187].map(key => {const button=new Element();button.dataset.key=String(key);return button;});
  const document = Object.assign(new Element(), {hidden:false,getElementById:element,querySelectorAll:() => keys});
  const window = new Element();
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url=url;this.readyState=1;this.bufferedAmount=0;this.sent=[];this.closed=false;sockets.push(this); }
    send(data) { this.sent.push(JSON.parse(data)); }
    close() { this.closed=true;this.readyState=3; }
    message(data) { this.onmessage?.({data: data instanceof ArrayBuffer ? data : JSON.stringify(data)}); }
  }
  class Decoder {
    static async isConfigSupported() { return {supported:true}; }
    constructor(callbacks) { this.callbacks=callbacks;this.state='unconfigured';this.decodeQueueSize=0;decoders.push(this); }
    configure() { this.state='configured'; }
    reset() { this.state='unconfigured'; }
    close() { this.state='closed'; }
    decode() {}
    frame() { this.callbacks.output({displayWidth:1080,displayHeight:2160,close(){}}); }
  }
  const replacements = {
    document,window,location:{href:'https://example.test/'},isSecureContext:true,
    WebSocket:Socket,VideoDecoder:Decoder,EncodedVideoChunk:class {constructor(value){Object.assign(this,value);}},
    setInterval:callback => {intervals.add(callback);return callback;},clearInterval:callback => intervals.delete(callback),
    fetch:async (url,options={}) => {
      requests.push({url,options});
      const value = options.method === 'POST'
        ? {ok:true,session:{id:'b'.repeat(32),viewer_token:'fixture-viewer-token',viewer_path:'/api/phone/stream/fixture/viewer'}}
        : url.endsWith('/status') ? {ok:true,device:{online:true,capabilities:{screen_stream:true}}} : {ok:true};
      return {ok:true,json:async () => value};
    },
  };
  const originals = new Map(Object.keys(replacements).map(key => [key,Object.getOwnPropertyDescriptor(globalThis,key)]));
  for (const [key,value] of Object.entries(replacements)) Object.defineProperty(globalThis,key,{configurable:true,writable:true,value});
  t.after(() => {
    for (const [key,descriptor] of originals) {
      if (descriptor) Object.defineProperty(globalThis,key,descriptor);
      else delete globalThis[key];
    }
  });
  return {requests,sockets,decoders,intervals,document,window,element,keys};
}

async function settle() { for (let i=0;i<12;i++) await Promise.resolve(); }
function videoPacket() {
  const bytes = Uint8Array.from([3,0,0,0,0,0,0,0,1, 0,0,0,1,0x67,0x42,0,0x1e, 0,0,1,0x68,0xce, 0,0,1,0x65,1]);
  return bytes.buffer;
}
async function showFrame(fixture, socket) {
  socket.message({type:'video',codec:'h264',width:1080,height:2160});
  socket.message(videoPacket());
  await settle();
  fixture.decoders.at(-1).frame();
}
const inputs = socket => socket.sent.filter(message => ['touch','key','text','take_control'].includes(message.type));

test('observe stream sends no automatic input and waits for server takeover confirmation', async t => {
  const fixture = browserFixture(t), updates = [], failures = [];
  const viewer = new PhoneViewer({canvas:fixture.element('screen'),base:'/api/phone',token:'fixture-only-token',update:(...args)=>updates.push(args),resized(){},failure:message=>failures.push(message)});
  t.after(() => viewer.stop());
  viewer.start(); await viewer.opening;
  assert.deepEqual(JSON.parse(fixture.requests[0].options.body),{mode:'observe'});
  const socket = fixture.sockets[0]; socket.onopen();
  socket.message({type:'status',state:'connected',control_active:false});
  assert.equal(viewer.pointer(0,1,10,20),false);
  viewer.key(3); assert.equal(viewer.text('early'),false);
  await showFrame(fixture,socket);
  for (const heartbeat of fixture.intervals) heartbeat();
  assert.equal(viewer.ready,true);
  assert.deepEqual(inputs(socket),[]);
  assert.ok(socket.sent.some(message => message.type==='reset_video'));
  assert.ok(socket.sent.some(message => message.type==='ping'));
  assert.equal(updates.at(-1)[2],false);
  assert.equal(viewer.text(''),false);
  assert.equal(viewer.pointer(2,1,10,20),false);
  viewer.cancelTouches();
  assert.deepEqual(inputs(socket),[]);
  assert.equal(viewer.pointer(0,1,10,20),true);
  viewer.pointer(2,1,20,30); viewer.pointer(1,1,20,30);
  viewer.key(4); viewer.text('你好');
  assert.deepEqual(inputs(socket).map(message => message.type),['touch','touch','touch','key','text']);
  assert.deepEqual(inputs(socket).slice(0,3).map(message => message.action),[0,2,1]);
  assert.deepEqual(inputs(socket)[0],{type:'touch',action:0,pointer_id:0,width:1080,height:2160,x:108,y:216});
  assert.equal(viewer.controlActive,false);
  assert.equal(updates.at(-1)[2],false);
  socket.message({type:'status',state:'connected',control_active:true});
  assert.equal(viewer.controlActive,true);
  assert.equal(updates.at(-1)[2],true);
  assert.match(updates.at(-1)[1],/^已接管/);
  assert.deepEqual(failures,[]);
  await viewer.stop();
});

test('legacy server status fails closed, deletes the session, and explains the upgrade', async t => {
  const fixture = browserFixture(t), failures = [];
  const viewer = new PhoneViewer({canvas:fixture.element('screen'),base:'/api/phone',token:'fixture-only-token',update(){},resized(){},failure:message=>failures.push(message)});
  viewer.start(); await viewer.opening;
  const socket=fixture.sockets[0];socket.onopen();
  socket.message({type:'status',state:'connected'});
  await viewer.stop();
  assert.match(failures[0],/升级 server/);
  assert.equal(viewer.authenticated,false);
  assert.equal(viewer.controlActive,null);
  assert.equal(socket.closed,true);
  assert.deepEqual(inputs(socket),[]);
  assert.equal(fixture.requests.filter(request => request.options.method==='DELETE').length,1);
  assert.match(friendlyError('screen_stream_observe_unsupported'),/升级手机伙伴/);
  assert.match(friendlyError('control_takeover_timeout'),/未及时确认接管/);
  assert.match(friendlyError('control_takeover_failed'),/未能完成接管/);
});

test('page events show ACK status, stop on background, and reconnect in observe mode', async t => {
  const fixture = browserFixture(t);
  await import('./app.js?observe-fixture');
  fixture.element('token').value='fixture-only-token';
  await fixture.element('connect-form').emit('submit');await settle();
  const first=fixture.sockets[0];first.onopen();
  first.message({type:'status',state:'connected',control_active:false});
  await showFrame(fixture,first);
  assert.equal(fixture.element('status-badge').span.textContent,'正在旁观');
  assert.equal(fixture.element('screen').hidden,false);
  assert.deepEqual(inputs(first),[]);
  await fixture.keys[0].emit('click');
  assert.equal(fixture.element('status-badge').span.textContent,'正在旁观');
  first.message({type:'status',state:'connected',control_active:true});
  assert.equal(fixture.element('status-badge').span.textContent,'已接管');
  fixture.document.hidden=true;
  await fixture.document.emit('visibilitychange');await settle();
  assert.equal(first.closed,true);
  assert.equal(fixture.element('screen').hidden,true);
  fixture.document.hidden=false;
  await fixture.document.emit('visibilitychange');
  assert.equal(fixture.sockets.length,1);
  await fixture.element('connect-form').emit('submit');await settle();
  const second=fixture.sockets[1];second.onopen();
  second.message({type:'status',state:'connected',control_active:false});
  await showFrame(fixture,second);
  assert.equal(fixture.element('status-badge').span.textContent,'正在旁观');
  assert.deepEqual(inputs(second),[]);
  const posts=fixture.requests.filter(request => request.options.method==='POST');
  assert.deepEqual(posts.map(request => JSON.parse(request.options.body)),[{mode:'observe'},{mode:'observe'}]);
  await fixture.window.emit('pagehide');await settle();
  assert.equal(second.closed,true);
  assert.equal(fixture.element('token').value,'');
});
