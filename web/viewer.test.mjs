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
