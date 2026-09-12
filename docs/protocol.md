# Phone for AI · v0.1 wire contract

This document is the integration contract for the standalone public extraction. The Android app accepts a full API base URL, normally `https://phone.example.com/api/phone`. There is no built-in deployment URL. The server defaults to loopback port 8765. One server instance pairs with one phone; only one command is active at a time.

## Authentication and pairing

- Operator endpoints require `Authorization: Bearer <operator-token>` from local configuration. This token never goes in the APK.
- `POST /enrollment` (operator): create a random one-use expiring pairing code. Response includes `ok`, `enrollment_code`, `expires_at`. Explicit new pairing revokes the previous device and expires its outstanding commands.
- `POST /enroll` (unauthenticated): `{enrollment_code, app_version, capabilities}` → `{ok:true, device_id, device_token}`. Code is consumed atomically. Invalid/used/expired codes fail. Stored token/code verifiers are hashes.
- Phone endpoints use the separate `device_token` as a bearer token. Tokens are not logged or included in status responses. HTTPS is required by the Android app.

## Phone endpoints

- `POST /heartbeat`: `{app_version, capabilities, status, long_poll_seconds:20}`. `status` may include `battery_pct`, `plugged`, `screen_on`, `accessibility`. Returns `{ok, server_time, long_poll_seconds, heartbeat_interval_seconds:2, command?}`. Long polls release the state lock. A pending command is delivered once, persisted as delivered, and never automatically replayed after a dropped response.
- `POST /commands/{id}/artifact`: JPEG bytes, `Content-Type: image/jpeg`, at most 3 MiB. Only for a command delivered to this device. Upload precedes the result; returning an artifact receipt is not command completion.
- `POST /commands/{id}/result`: `{ok:boolean, result:object, error?:string}`. Persist final receipt. A repeated identical result can return the existing receipt, but a different result cannot overwrite it. Screenshot upload failure must still send the result with `artifact_upload_failed:true`; it must never rerun the action.

## Operator endpoints

- `GET /status`: `{ok, device?, active_command?, latest_command?}`. Device includes online/last_seen/capabilities/status. No secrets or server file paths.
- `POST /commands`: `{type, args, ttl_seconds?:600}` → `{ok, command}`.
- `GET /commands/{id}` → `{ok, command}`.
- `POST /commands/{id}/cancel`: cancel pending or abandon delivered commands. Abandoning a delivered action cannot undo it or prove it did not happen; retain that distinction in the receipt. Do not delete finished receipts.
- `GET /commands/{id}/artifact`: authenticated JPEG download. MCP must fetch bytes from this endpoint rather than open server-local paths.

Command envelope: `id`, `type`, `args`, `status` (`pending`, `delivered`, `completed`, `failed`, `cancelled`, `expired`), `created_at`, `expires_at`, `delivered_at`, `completed_at`, `ok`, `result`, `error`, optional `artifact`. Timestamps are ISO 8601 UTC. `result` preserves binary fields as Base64 and real process `exit_code`, `timed_out`, `truncated`. Screenshot metadata: `package_name`, `image_width`, `image_height`, `display_width`, `display_height`.

## Generic commands

Keep the existing generic argument shapes; omit all application-specific messaging, database-reading and red-packet commands. Live viewing is a separate authenticated session as described below.

| Type | Arguments |
| --- | --- |
| `screen.capture`, `screen.wake` | `max_width`, `quality` |
| `app.launch` | `package`, `max_width`, `quality` |
| `ui.tap` | basis fields, `x`, `y`, screenshot options |
| `ui.long_press` | basis fields, `x`, `y`, `duration_ms`, screenshot options |
| `ui.swipe` | basis fields, `x1`, `y1`, `x2`, `y2`, `duration_ms`, screenshot options |
| `ui.type` | basis fields, `text`, screenshot options; exact replacement, preserve whitespace; allow empty string to clear |
| `ui.global.back`, `ui.global.home`, `ui.global.recents` | basis fields, screenshot options |
| `ui.dump` | `max_nodes`, `max_depth` |
| `clipboard.set` | `text`; allow empty string |
| `root.exec` | `command`, `root` (default true), `timeout_seconds`, `max_bytes` |
| `apps.list` | `query`, `user_only`, `max_bytes` |
| `files.list` | `path`, `max_bytes` |
| `files.read` | `path`, `offset`, `max_bytes`; result `data_base64`, `next_offset`, `eof` |
| `files.write` | `path`, `data_base64`, `append`; at most 49152 decoded bytes per call |

UI basis fields: `basis_command_id`, `expected_package`. A UI action consumes the latest successful screenshot once. Reject stale (>180 seconds), consumed, mismatched-package, wrong-device or non-screenshot bases on the server and recheck the current foreground package and local screenshot basis on the phone. Coordinates refer to `display_width`/`display_height`, not the resized JPEG. Capture defaults 1080/q72; action screenshots 720/q60; options 540–1440 px, quality 50–90. This first release follows the existing Android 14+ AccessibilityService screen-capture path; secure keyguards require the person to unlock. A root action timing out or being cancelled does not undo a side effect.

Capabilities: `accessibility`, `screen_capture`, `wake_screen`, `gestures`, `text_input`, `launch`, `ui_tree`, `clipboard`, `root_shell`, `file_access`, `app_inventory`, `screen_stream`. Advertise implementation availability honestly; actual Root denial remains an execution failure.

## Live browser control

The browser console is served at `/`. It keeps the operator token in memory, sends it in HTTP authorization headers, and never places tokens in URLs or local storage. The stream extension uses the existing scrcpy 4.1 H.264 transport, with Root required on the phone.

- `POST /stream` (operator, body `{}`) → `{ok, session:{id,viewer_token,viewer_path}}`.
- `DELETE /stream/{id}` (operator) ends the session idempotently.
- WS `/stream/{id}/viewer`: first text frame `{type:"auth",token:<short-lived viewer token>}` within 5 seconds; one viewer, attach within 30 seconds.
- WS `/stream/{id}/device`: first text frame `{type:"auth",token:<device token>}`. The heartbeat's optional `stream` object requests the current session only after a viewer authenticates. Android uses the paired API base's origin plus the server-provided device path.
- Viewer sends `ping` at 10-second intervals; relay replies `pong`. A viewer silent for 30 seconds expires. Hidden/unloaded pages close their socket and delete their session; losing a socket stops Root capture/control.
- Server sends JSON `status` (`waiting` or `connected`), `video` (`codec:"h264",width,height`), `error`, and `stopped`.
- Video binary packets: 1 flag byte (bit0 config, bit1 keyframe), 8-byte big-endian microsecond timestamp, Annex-B H.264 payload. Limit 2 MiB. Browser uses WebCodecs, bounded decode queue, cached SPS/PPS, reset/keyframe recovery, and releases every decoded frame.
- Controls: `{type:"touch",action:0|1|2|3,pointer_id:0..9,x,y,width,height}`, `{type:"key",keycode:3|4|187|24|25|66|67}`, `{type:"text",text:<at most 2000 characters>}`, `{type:"reset_video"}`. Touch coordinates use displayed video dimensions. Cancel active fingers before rotation, decoder reset, blur, failure, or stop.
- A live authenticated viewer owns phone control. UI commands, launch, wake, clipboard and shell/file mutation must be blocked while viewing; creating/attaching a viewer rechecks command activity. No queued touch/text is replayed when reconnecting. Frames are relayed only; no video recording is created.

## Public scope

All server state and screenshot artifacts are private runtime files outside Git. Provide a configurable state directory, restrictive permissions, bounded request/result sizes, bounded subprocesses, and screenshot retention. A single worker process owns durable state. UI remains under the person's control; an operator token grants phone control and must be kept private. The bridge does not choose shopping budgets or approve purchases: those are instructions given to the agent by its person.
