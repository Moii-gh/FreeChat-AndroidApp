const { Client } = require('ssh2');

function test(username, host, password) {
  console.log(`Testing ${username}@${host}...`);
  const conn = new Client();
  conn.on('ready', () => {
    console.log(`SUCCESS with ${username}`);
    conn.exec('whoami', (err, stream) => {
       stream.on('close', () => conn.end()).on('data', (d) => console.log(d.toString()));
    });
  }).on('error', (err) => {
    console.log(`FAILED with ${username}: ${err.message}`);
  }).connect({
    host,
    port: 22,
    username,
    password,
    tryKeyboard: true
  });
}

test('root', '31.31.197.18', 'WjveAP2rOA5QQ581');
test('u3495823', '31.31.197.18', 'WjveAP2rOA5QQ581');
