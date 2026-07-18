// Registers this backend as the device's HTTP notification host.
//   node src/registerWebhook.js          -> write config
//   node src/registerWebhook.js --show   -> read current config
const config = require('./config');
const { IsapiClient } = require('./isapi');

const client = new IsapiClient();

async function show() {
  console.log(JSON.stringify(await client.httpHosts(), null, 2));
}

async function register() {
  // Fail early with a clear message if credentials or the address are wrong,
  // rather than after a half-applied config.
  const { DeviceInfo } = await client.deviceInfo();
  console.log(`Connected: ${DeviceInfo?.model} (fw ${DeviceInfo?.firmwareVersion})`);

  await client.registerHttpHost({
    host: config.listenerHost,
    port: config.port,
    path: config.webhookPath,
    user: config.webhookUser,
    pass: config.webhookPass,
  });

  console.log(
    `Device will now POST to http://${config.listenerHost}:${config.port}${config.webhookPath}`
  );
}

const run = process.argv.includes('--show') ? show : register;

run().catch((err) => {
  console.error(`Failed talking to ${client.base}: ${err.message}`);
  if (err.subStatusCode) console.error(`subStatusCode: ${err.subStatusCode}`);
  process.exit(1);
});
