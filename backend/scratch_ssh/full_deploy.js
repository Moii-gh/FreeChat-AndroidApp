const { Client } = require('ssh2');
const fs = require('fs');
const path = require('path');

const config = {
  host: '31.31.197.18',
  port: 22,
  username: 'u3495823',
  password: 'WjveAP2rOA5QQ581'
};

const localBackendDir = path.resolve(__dirname, '..');
const remoteBackendDir = '/var/www/u3495823/data/www/free-chat.online';

const conn = new Client();

function uploadDir(sftp, localPath, remotePath, callback) {
  fs.readdir(localPath, { withFileTypes: true }, (err, files) => {
    if (err) return callback(err);
    
    let i = 0;
    function next() {
      if (i >= files.length) return callback();
      const file = files[i++];
      if (file.name === 'node_modules' || file.name === '.git' || file.name === 'scratch_ssh') {
         return next();
      }
      
      const local = path.join(localPath, file.name);
      const remote = path.posix.join(remotePath, file.name);
      
      if (file.isDirectory()) {
        sftp.mkdir(remote, (err) => {
          // Ignore error if directory exists
          uploadDir(sftp, local, remote, (err) => {
            if (err) return callback(err);
            next();
          });
        });
      } else {
        console.log(`Uploading ${local} -> ${remote}`);
        sftp.fastPut(local, remote, (err) => {
          if (err) return callback(err);
          next();
        });
      }
    }
    next();
  });
}

conn.on('ready', () => {
  console.log('SSH Connected');
  conn.sftp((err, sftp) => {
    if (err) throw err;
    
    uploadDir(sftp, localBackendDir, remoteBackendDir, (err) => {
      if (err) {
        console.error('Upload failed:', err);
        conn.end();
      } else {
        console.log('Upload complete. Running npm install...');
        // We'll run npm install in a separate exec call
        conn.exec(`cd ${remoteBackendDir} && npm install --production`, (err, stream) => {
           if (err) throw err;
           stream.on('close', (code) => {
             console.log(`npm install exited with code ${code}`);
             conn.end();
           }).on('data', (d) => process.stdout.write(d))
           .stderr.on('data', (d) => process.stderr.write(d));
        });
      }
    });
  });
}).connect(config);
