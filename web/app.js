import { PhoneViewer, friendlyError } from './viewer.js';

const $ = id => document.getElementById(id);
const base = '/api/phone';
let token = '', viewer = null, stopping = Promise.resolve(), busy = false;
let snapshotUrl = null, pendingCapture = null, operation = 0;

function setState(phase, message) {
  const live = phase === 'live';
  $('status-badge').dataset.phase = phase;
  $('status-badge').querySelector('span').textContent = ({ idle:'等待连接',connecting:'连接中',waiting:'等待画面',live:'实时连接',paused:'已暂停',snapshot:'单张截图',error:'暂未连接' })[phase] || phase;
  $('status-message').textContent = message;
  $('screen').hidden = !live;
  $('empty-screen').hidden = live || phase === 'snapshot';
  if (phase !== 'snapshot') $('snapshot-image').hidden = true;
  $('empty-title').textContent = phase === 'error' ? '还没有接上画面。' : phase === 'paused' ? '这扇窗，休息一下。' : phase === 'waiting' || phase === 'connecting' ? '正在等手机过来…' : '这里，会是你的手机。';
  $('empty-detail').textContent = phase === 'error' ? '看下方说明，处理后再连接' : phase === 'paused' ? '点「连接实时画面」即可继续' : '第一次连接前\n先让手机伙伴完成配对';
  document.querySelectorAll('[data-key]').forEach(button => button.disabled = !live);
  $('phone-text').disabled = !live;
  $('send-text').disabled = !live || !$('phone-text').value;
  $('disconnect').disabled = !viewer && !busy;
  $('connect').disabled = busy || !!viewer;
  $('snapshot').disabled = busy;
}

function getToken() {
  const value = $('token').value.trim();
  if (value) { token = value; $('token').value = ''; }
  if (!token) throw new Error('请先粘贴控制密钥，或选择自己的 operator.token 文件。');
  return token;
}

async function request(path, options = {}) {
  const response = await fetch(base + path, {
    ...options, cache:'no-store', headers: { Authorization:`Bearer ${token}`, ...(options.body ? {'Content-Type':'application/json'} : {}), ...options.headers },
  });
  const result = await response.json();
  if (!response.ok || !result.ok) throw new Error(friendlyError(result.error || `HTTP ${response.status}`));
  return result;
}

async function deviceInfo() {
  const status = await request('/status');
  const device = status.device;
  if (!device) throw new Error(friendlyError('device_unavailable'));
  const battery = device.status?.battery_pct;
  $('device-detail').textContent = [device.online ? '手机在线' : '等待手机联网', Number.isFinite(battery) ? `电量 ${battery}%` : '', device.status?.plugged ? '正在充电' : '', device.capabilities?.screen_stream ? '实时投屏已开启' : '可先试单张截图；实时投屏需要 Root'].filter(Boolean).join(' · ');
  return device;
}

async function stop() {
  const current = viewer; viewer = null;
  if (current) stopping = current.stop();
  await stopping;
}

$('connect-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || viewer) return;
  const ticket = ++operation;
  busy = true;
  setState('connecting','正在确认手机连接…');
  try {
    getToken(); await stop(); await deviceInfo();
    if (ticket !== operation || document.hidden) return;
    const current = new PhoneViewer({
      canvas:$('screen'),base,token,
      update:(phase,message) => { if (viewer === current) setState(phase,message); },
      resized:({w,h}) => { $('phone-shell').style.aspectRatio = `${w}/${h}`; },
      failure:message => {
        if (viewer !== current) return;
        viewer = null; stopping = current.stop();
        setState('error',message);
      },
    });
    viewer = current;
    current.start();
  } catch (error) { setState('error',error.message); }
  finally { busy = false; $('connect').disabled = !!viewer; $('snapshot').disabled = false; $('disconnect').disabled = !viewer; }
});

$('disconnect').addEventListener('click', async () => {
  ++operation;
  await stop();
  setState('paused','已经停止传屏和控制，agent 可以继续操作手机。');
});

$('snapshot').addEventListener('click', async () => {
  if (busy) return;
  const ticket = ++operation;
  busy = true;
  try {
    getToken(); await stop();
    setState('connecting', pendingCapture ? '正在取回上一张截图…' : '正在让手机拍一张截图…');
    await deviceInfo();
    if (ticket !== operation || document.hidden) return;
    if (!pendingCapture) {
      const queued = await request('/commands',{method:'POST',body:JSON.stringify({type:'screen.capture',args:{max_width:1080,quality:72}})});
      pendingCapture = queued.command.id;
    }
    let command;
    const deadline = Date.now() + 45_000;
    while (Date.now() < deadline && ticket === operation && !document.hidden) {
      command = (await request(`/commands/${encodeURIComponent(pendingCapture)}`)).command;
      if (!['pending','delivered'].includes(command.status)) break;
      await new Promise(resolve => setTimeout(resolve,750));
    }
    if (ticket !== operation || document.hidden) return;
    if (!command || ['pending','delivered'].includes(command.status)) {
      $('snapshot').textContent = '取回上一张截图';
      throw new Error('手机还没返回这张截图。稍后点「取回上一张截图」，不会重新创建截图指令。');
    }
    const id = pendingCapture; pendingCapture = null;
    $('snapshot').textContent = '查看一张截图';
    if (!command.ok || command.status !== 'completed') throw new Error(friendlyError(command.error || 'capture_failed'));
    const response = await fetch(`${base}/commands/${encodeURIComponent(id)}/artifact`,{headers:{Authorization:`Bearer ${token}`},cache:'no-store'});
    if (!response.ok) throw new Error('截图指令已结束，但图片没有取到。请检查手机截图上传状态。');
    const blob = await response.blob();
    if (ticket !== operation || document.hidden) return;
    if (snapshotUrl) URL.revokeObjectURL(snapshotUrl);
    snapshotUrl = URL.createObjectURL(blob);
    $('snapshot-image').src = snapshotUrl; $('snapshot-image').hidden = false;
    const w = command.result?.image_width, h = command.result?.image_height;
    if (w && h) $('phone-shell').style.aspectRatio = `${w}/${h}`;
    setState('snapshot','这是单张截图。连接实时画面后，就能直接点按手机。');
  } catch (error) { setState('error',error.message); }
  finally { busy = false; $('connect').disabled = !!viewer; $('snapshot').disabled = false; $('disconnect').disabled = !viewer; }
});

$('token-file').addEventListener('change',async event => {
  const file = event.target.files?.[0];
  if (!file) return;
  if (file.size > 4096) { setState('error','请选择自己的 operator.token 小文本文件。'); return; }
  token = (await file.text()).trim(); $('token').value = ''; event.target.value = '';
  $('token-hint').textContent = '密钥已读取，仅保存在当前页面。';
});

for (const [name,action] of [['pointerdown',0],['pointermove',2],['pointerup',1]]) {
  $('screen').addEventListener(name,event => {
    if (action === 0 && event.pointerType === 'mouse' && event.button !== 0) return;
    event.preventDefault();
    const sent = viewer?.pointer(action,event.pointerId,event.clientX,event.clientY);
    if (action === 0 && sent) {
      try { event.target.setPointerCapture(event.pointerId); }
      catch { viewer?.cancelPointer(event.pointerId); }
    }
    if (action === 1 && event.target.hasPointerCapture(event.pointerId)) event.target.releasePointerCapture(event.pointerId);
  });
}
for (const name of ['pointercancel','lostpointercapture']) $('screen').addEventListener(name,event => viewer?.cancelPointer(event.pointerId));
$('screen').addEventListener('contextmenu',event => event.preventDefault());
document.querySelectorAll('[data-key]').forEach(button => button.addEventListener('click',() => viewer?.key(Number(button.dataset.key))));
$('phone-text').addEventListener('input',() => {
  $('text-count').textContent = `${$('phone-text').value.length} / 2000`;
  $('send-text').disabled = !viewer?.ready || !$('phone-text').value;
});
$('text-form').addEventListener('submit',event => {
  event.preventDefault();
  if (viewer?.text($('phone-text').value)) {
    $('phone-text').value = ''; $('text-count').textContent = '0 / 2000'; $('send-text').disabled = true;
    $('status-message').textContent = '文字已递给手机，请看输入框确认；尚未点击发送。';
  }
});
function leave() {
  ++operation;
  void stop();
  setState('paused','离开页面后已停止控制。回来时点连接即可继续。');
}
document.addEventListener('visibilitychange',() => { if (document.hidden) leave(); });
window.addEventListener('pagehide',leave);
window.addEventListener('blur',() => viewer?.cancelTouches());
