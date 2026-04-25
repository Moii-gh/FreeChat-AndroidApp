const { Client } = require('ssh2');

const conn = new Client();
const nginxConfig = `
server {
    listen 80;
    server_name free-chat.online www.free-chat.online;

    location / {
        proxy_pass http://127.0.0.1:4000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
`;

conn.on('ready', () => {
  console.log('Connected to server');
  
  // 1. Install nginx
  // 2. Write nginx config
  // 3. Enable site and restart nginx
  // 4. Update .env
  // 5. Restart backend service
  const cmds = [
    'apt-get update',
    'apt-get install -y nginx',
    `cat << 'EOF' > /etc/nginx/sites-available/freechat.conf
${nginxConfig}EOF`,
    'ln -sf /etc/nginx/sites-available/freechat.conf /etc/nginx/sites-enabled/',
    'rm -f /etc/nginx/sites-enabled/default',
    'systemctl restart nginx',
    'sed -i "s|^CHAT_SHARE_PUBLIC_BASE_URL=.*|CHAT_SHARE_PUBLIC_BASE_URL=https://free-chat.online|g" /opt/chatapp-backend/.env',
    'systemctl restart chatapp-auth.service'
  ];
  
  conn.exec(cmds.join(' && '), (err, stream) => {
    if (err) throw err;
    stream.on('close', () => {
      console.log('Server setup complete.');
      conn.end();
    })
    .on('data', (d) => process.stdout.write(d))
    .stderr.on('data', (d) => process.stderr.write(d));
  });
}).connect({
  host: '138.124.97.180',
  port: 22,
  username: 'root',
  password: '103L84cc569l'
});
