require('dotenv').config();

const bool = (v, d = false) =>
  v === undefined ? d : ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase());

// Stray spaces around a value in .env ("DEVICE_HOST= 10.0.0.5") otherwise end up
// inside the URL and fail with an opaque connection error.
const str = (v, d = '') => (v === undefined ? d : String(v).trim());

module.exports = {
  port: Number(str(process.env.PORT) || 8080),
  listenerHost: str(process.env.LISTENER_HOST) || '127.0.0.1',
  webhookPath: str(process.env.WEBHOOK_PATH) || '/hik/event',
  webhookUser: str(process.env.WEBHOOK_USER),
  webhookPass: str(process.env.WEBHOOK_PASS),
  savePictures: bool(process.env.SAVE_PICTURES, true),
  // Log every inbound request (method, headers, body) and keep the last few in
  // memory for GET /debug/requests. Useful when bringing a new device online.
  debugRequests: bool(process.env.DEBUG_REQUESTS, false),

  device: {
    host: str(process.env.DEVICE_HOST) || '192.168.1.64',
    port: Number(str(process.env.DEVICE_PORT) || 80),
    user: str(process.env.DEVICE_USER) || 'admin',
    pass: str(process.env.DEVICE_PASS),
  },
};
