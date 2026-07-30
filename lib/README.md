# hikvision-event-parser.js

A single, **zero-dependency** file to parse a Hikvision access terminal's webhook
event into clean JSON. Copy `hikvision-event-parser.js` into your backend — it
needs nothing else from this repo.

## What it does

You give it the webhook request (or just the body); it returns a flat event
object. It handles every shape a terminal sends:

- `application/json` (alert object)
- **XML** with a `text/plain` content-type (some firmwares)
- `multipart/form-data` (a JSON/XML part + JPEG snapshots) — parsed or raw
- an already-parsed object, a raw string, or a `Buffer`

Two files, same logic — use whichever your backend is:
- `hikvision-event-parser.js` — CommonJS, plain Node
- `hikvision-event-parser.ts` — TypeScript, fully typed (`DeviceEvent` return type)

## Use (JavaScript)

```js
const { parseDeviceEvent } = require('./hikvision-event-parser');

app.post('/webhook', (req, res) => {
  res.sendStatus(200);                 // ack fast — the device retries on non-2xx
  const event = parseDeviceEvent(req);
  if (!event || event.isHeartbeat) return;   // skip unparseable + keep-alives
  saveAttendance(event);               // your logic
});
```

## Use (TypeScript)

```ts
import { parseDeviceEvent, DeviceEvent } from './hikvision-event-parser';

app.post('/webhook', (req: Request, res: Response) => {
  res.sendStatus(200);
  const event: DeviceEvent | null = parseDeviceEvent(req);
  if (!event || event.isHeartbeat) return;
  saveAttendance(event);               // event is fully typed
});
```

- Needs `@types/node` (for `Buffer`). Compiles under `tsc`/`tsx`/`esbuild`/ts-node.
- Under Node's raw `--experimental-strip-types`, import the type separately:
  `import { parseDeviceEvent } from '...'; import type { DeviceEvent } from '...';`
  (normal TS toolchains don't need this).
- Exported types: `DeviceEvent`, `VerifyMethod`, `RequestLike`, `ParseOptions`, `ParserInput`.

Already have the body?

```js
const event = parseDeviceEvent(rawStringOrObject, { contentType });
```

> For the **multipart** case with a raw (unparsed) body, pass an object with the
> raw bytes on `rawBody` (or `body`) and the `content-type` header — the parser
> extracts the text part itself. If your framework already parsed multipart into
> fields (e.g. multer), just pass `req` — it reads the `event_log` field.

## Returns

`null` if unparseable, otherwise:

```json
{
  "serialNo": 42,
  "deviceIp": "192.168.1.50",
  "eventType": "AccessControllerEvent",
  "majorType": 5,
  "minorType": 113,
  "eventName": "fingerprintAuthSuccess",
  "verifyMethod": "fingerprint",
  "success": 1,
  "employeeNo": "E100",
  "personName": "Kamal",
  "cardNo": null,
  "verifyMode": "fingerprint",
  "attendance": null,
  "doorNo": 1,
  "eventTime": "2026-07-29T09:05:00+05:30",
  "picturePath": null,
  "raw": "{...original alert...}",
  "receivedAt": "2026-07-29T03:35:00.000Z",
  "isHeartbeat": false
}
```

- `eventName`/`verifyMethod` come from the **minor code** (fingerprint 113, card 38,
  face 75/76, exit-button 27). If `eventName` is `null` the code is unmapped —
  look at `raw` and add it to the `MINOR` map in the file.
- `isHeartbeat: true` marks keep-alives (no credential) — ignore those.

## Exports

`parseDeviceEvent` · `normalise` · `isHeartbeat` · `describe` · `xmlToObject` ·
`MAJOR` · `MINOR`

> ESM: `import parser from './hikvision-event-parser.js'` then
> `parser.parseDeviceEvent(...)` (CommonJS interop).
