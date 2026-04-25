const { Client } = require('ssh2');
const conn = new Client();
conn.on('ready', () => {
  conn.exec('ls -F www', (err, stream) => {
    if (err) throw err;
    stream.on('close', () => conn.end())
    .on('data', (d) => process.stdout.write(d))
    .stderr.on('data', (d) => process.stderr.write(d));
  });
}).connect({
  host: '31.31.197.18',
  port: 22,
  username: 'u3495823',
  password: 'WjveAP2rOA5QQ581',
  readyTimeout: 30000
});
