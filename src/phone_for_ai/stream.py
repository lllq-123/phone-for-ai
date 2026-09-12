"""Ephemeral authenticated H.264/control relay; no frames or credentials are persisted."""
from __future__ import annotations

import asyncio, hmac, json, math, re, secrets, time
from dataclasses import dataclass, field
from typing import Any
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route, WebSocketRoute
from starlette.websockets import WebSocket, WebSocketDisconnect
from .core import BridgeService

BASE_PATH = "/api/phone/stream"
ATTACH_TIMEOUT = 30.0
AUTH_TIMEOUT = 5.0
VIEWER_TIMEOUT = 30.0
SEND_TIMEOUT = 2.0
MAX_BINARY = 2 * 1024 * 1024
MAX_JSON = 16 * 1024
MAX_DIMENSION = 16384
ERROR_RE = re.compile(r"^[a-z][a-z0-9_]{0,95}$")

class ProtocolError(Exception): pass

@dataclass
class Peer:
    socket: WebSocket
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    async def send(self, value: dict[str, Any] | bytes) -> None:
        async def write():
            async with self.lock:
                if isinstance(value, bytes): await self.socket.send_bytes(value)
                else: await self.socket.send_json(value)
        await asyncio.wait_for(write(), SEND_TIMEOUT)
    async def close(self, code: int = 1000) -> None:
        try:
            async with self.lock: await asyncio.wait_for(self.socket.close(code=code), SEND_TIMEOUT)
        except BaseException: pass

@dataclass
class Session:
    id: str
    device_id: str
    viewer_token: str
    created: float = field(default_factory=time.monotonic)
    viewer_seen: float = 0
    viewer: Peer | None = None
    device: Peer | None = None
    stopping: bool = False
    ended: asyncio.Event = field(default_factory=asyncio.Event)
    finished: asyncio.Event = field(default_factory=asyncio.Event)
    expiry: asyncio.TimerHandle | None = None

class StreamRelay:
    def __init__(self, service: BridgeService): self.service, self.session = service, None
    def is_active(self) -> bool:
        s = self.session
        return bool(s and s.viewer and (s.stopping or time.monotonic() - s.viewer_seen < VIEWER_TIMEOUT))
    def desired_stream(self, device_id: str) -> dict[str, Any] | None:
        s = self.session
        if not s or s.stopping or s.device_id != device_id or not self.is_active(): return None
        return {"id": s.id, "device_path": f"{BASE_PATH}/{s.id}/device", "max_size": 1280, "bit_rate": 1500000, "max_fps": 30}
    async def finish(self, s: Session, error: str | None = None) -> None:
        if s.stopping:
            await s.finished.wait()
            return
        s.stopping = True
        # Stop device streaming promptly while retaining exclusive control until
        # both peers have closed and self.session is cleared.
        self.service._notify()
        if s.expiry: s.expiry.cancel()
        s.ended.set()
        async def stop(p: Peer):
            try:
                if error: await p.send({"type":"error","error":error})
                await p.send({"type":"stopped"})
            except BaseException: pass
            await p.close(1011 if error else 1000)
        await asyncio.gather(*(stop(p) for p in (s.viewer, s.device) if p), return_exceptions=True)
        if self.session is s: self.session = None
        self.service._notify()
        s.finished.set()
    def operator(self, r: Request) -> bool:
        a = r.headers.get("authorization", "")
        return a.startswith("Bearer ") and self.service.operator_authorized(a[7:].strip())
    @staticmethod
    def error(code: str, status: int):
        return JSONResponse({"ok":False,"error":code}, status_code=status, headers={"Cache-Control":"no-store"})
    async def create(self, r: Request):
        if not self.operator(r): return self.error("unauthorized",401)
        body = await r.body()
        if len(body)>MAX_JSON: return self.error("payload_too_large",413)
        try:
            if json.loads(body or b"{}") != {}: return self.error("invalid_payload",400)
        except (ValueError,UnicodeError): return self.error("invalid_json",400)
        if self.session:
            if self.session.viewer is None and time.monotonic()-self.session.created>=ATTACH_TIMEOUT: await self.finish(self.session)
            else: return self.error("phone_stream_in_use",409)
        d=self.service.device_info()
        if not d: return self.error("device_unavailable",409)
        if (d.get("capabilities") or {}).get("screen_stream") is not True: return self.error("device_capability_missing",409)
        seen=d.get("last_seen")
        if type(seen) not in (int,float) or not math.isfinite(seen) or time.time()-seen>900: return self.error("device_offline",409)
        if self.service.command_busy(): return self.error("command_queue_busy",409)
        s=Session(secrets.token_hex(16),d["id"],secrets.token_urlsafe(32)); self.session=s
        def expire():
            if self.session is s and s.viewer is None: asyncio.create_task(self.finish(s))
        s.expiry=asyncio.get_running_loop().call_later(ATTACH_TIMEOUT,expire); self.service._notify()
        return JSONResponse({"ok":True,"session":{"id":s.id,"viewer_token":s.viewer_token,"viewer_path":f"{BASE_PATH}/{s.id}/viewer"}},status_code=201,headers={"Cache-Control":"no-store"})
    async def status(self,r:Request):
        if not self.operator(r): return self.error("unauthorized",401)
        s=self.session
        value=None if not s else {"id":s.id,"viewer_attached":s.viewer is not None,"device_attached":s.device is not None,"active":self.is_active(),"created_seconds_ago":max(0,int(time.monotonic()-s.created))}
        return JSONResponse({"ok":True,"session":value},headers={"Cache-Control":"no-store"})
    async def delete(self,r:Request):
        if not self.operator(r): return self.error("unauthorized",401)
        if self.session and self.session.id==r.path_params["session_id"]: await self.finish(self.session)
        return JSONResponse({"ok":True},headers={"Cache-Control":"no-store"})
    @staticmethod
    def message(m:dict[str,Any])->dict[str,Any]:
        if m["type"]=="websocket.disconnect": raise WebSocketDisconnect(m.get("code",1000))
        text=m.get("text")
        if not isinstance(text,str) or len(text.encode())>MAX_JSON: raise ProtocolError("invalid_message")
        try: p=json.loads(text)
        except ValueError: raise ProtocolError("invalid_json") from None
        if not isinstance(p,dict): raise ProtocolError("invalid_message")
        return p
    @staticmethod
    def integer(p,key,low,high):
        v=p.get(key)
        if type(v) is not int or not low<=v<=high: raise ProtocolError("invalid_control")
        return v
    def control(self,p):
        kind=p.get("type")
        if kind in {"ping","reset_video"}: return {"type":kind}
        if kind=="touch":
            w=self.integer(p,"width",1,MAX_DIMENSION); h=self.integer(p,"height",1,MAX_DIMENSION)
            return {"type":kind,"action":self.integer(p,"action",0,3),"pointer_id":self.integer(p,"pointer_id",0,9),"x":self.integer(p,"x",0,w-1),"y":self.integer(p,"y",0,h-1),"width":w,"height":h}
        if kind=="key":
            k=self.integer(p,"keycode",0,255)
            if k in {3,4,187,24,25,66,67}: return {"type":kind,"keycode":k}
        if kind=="text" and isinstance(p.get("text"),str) and len(p["text"])<=2000: return {"type":kind,"text":p["text"]}
        raise ProtocolError("invalid_control")
    async def auth(self,p:Peer):
        await asyncio.wait_for(p.socket.accept(),SEND_TIMEOUT); value=self.message(await asyncio.wait_for(p.socket.receive(),AUTH_TIMEOUT))
        if value.get("type")!="auth" or not isinstance(value.get("token"),str) or not value["token"] or len(value["token"])>1024: raise ProtocolError("unauthorized")
        return value
    async def viewer_socket(self,ws:WebSocket):
        p=Peer(ws); s=None; attached=False; error=None
        try:
            a=await self.auth(p); s=self.session
            if not s or s.id!=ws.path_params["session_id"] or not hmac.compare_digest(a["token"],s.viewer_token): raise ProtocolError("unauthorized")
            if s.viewer or s.stopping: raise ProtocolError("phone_stream_in_use")
            if time.monotonic()-s.created>=ATTACH_TIMEOUT:
                await self.finish(s); raise ProtocolError("session_expired")
            if self.service.command_busy():
                await self.finish(s); raise ProtocolError("command_queue_busy")
            s.viewer=p; s.viewer_seen=time.monotonic(); attached=True
            if s.expiry: s.expiry.cancel(); s.expiry=None
            self.service._notify(); await p.send({"type":"status","state":"waiting"})
            while not s.stopping:
                c=self.control(self.message(await asyncio.wait_for(ws.receive(),VIEWER_TIMEOUT))); s.viewer_seen=time.monotonic()
                if c["type"]=="ping": await p.send({"type":"pong"})
                elif s.device: await s.device.send(c)
        except ProtocolError as e: error=str(e)
        except asyncio.TimeoutError: error="stream_timeout"
        except WebSocketDisconnect: pass
        except Exception: error="stream_disconnected"
        finally:
            if attached and s: await self.finish(s,error)
            else: await p.close(4401 if error=="unauthorized" else 4409)
    async def device_socket(self,ws:WebSocket):
        p=Peer(ws); s=None; attached=False; error=None
        try:
            a=await self.auth(p); s=self.session
            if not s or s.id!=ws.path_params["session_id"] or self.service.authorized_device_id(a["token"])!=s.device_id or not self.is_active() or s.device: raise ProtocolError("unauthorized")
            s.device=p; attached=True; await s.viewer.send({"type":"status","state":"connected"}); metadata=False
            while not s.stopping:
                m=await ws.receive()
                if m["type"]=="websocket.disconnect": raise WebSocketDisconnect(m.get("code",1000))
                packet=m.get("bytes")
                if packet is not None:
                    if len(packet)>MAX_BINARY or not metadata or len(packet)<=9 or packet[0]&~3 or not (packet[9:13]==b"\0\0\0\1" or packet[9:12]==b"\0\0\1"): raise ProtocolError("invalid_video_packet")
                    await s.viewer.send(packet); continue
                v=self.message(m)
                if v.get("type")=="error": raise ProtocolError(v.get("error") if isinstance(v.get("error"),str) and ERROR_RE.fullmatch(v["error"]) else "device_stream_error")
                if v.get("type")!="video" or v.get("codec")!="h264": raise ProtocolError("invalid_video_metadata")
                w=self.integer(v,"width",1,MAX_DIMENSION); h=self.integer(v,"height",1,MAX_DIMENSION); metadata=True
                await s.viewer.send({"type":"video","codec":"h264","width":w,"height":h})
        except ProtocolError as e: error=str(e)
        except WebSocketDisconnect: error="device_disconnected"
        except Exception: error="stream_disconnected"
        finally:
            if attached and s: await self.finish(s,error)
            else: await p.close(4401 if error=="unauthorized" else 4409)
    def routes(self):
        return [Route(BASE_PATH,self.status,methods=["GET"]),Route(BASE_PATH,self.create,methods=["POST"]),Route(BASE_PATH+"/{session_id}",self.delete,methods=["DELETE"]),WebSocketRoute(BASE_PATH+"/{session_id}/viewer",self.viewer_socket),WebSocketRoute(BASE_PATH+"/{session_id}/device",self.device_socket)]
