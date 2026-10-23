const http = require('http');
const express = require('express');
const { WebSocketServer, WebSocket } = require('ws');
const os = require('os');
const path = require('path');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

// Serve static build of client if available
const clientDist = path.join(__dirname, '..', 'client', 'dist');
app.use(express.static(clientDist));

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// Rooms map: roomCode -> { host: ws, peers: Set<ws> }
const rooms = new Map();

function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const net of interfaces[name]) {
      // Skip over internal (i.e. 127.0.0.1) and non-ipv4 addresses
      if (net.family === 'IPv4' && !net.internal) {
        addresses.push({ interface: name, address: net.address });
      }
    }
  }
  return addresses;
}

wss.on('connection', (ws) => {
  let currentRoom = null;
  let isHost = false;

  ws.on('message', (message) => {
    let data;
    try {
      data = JSON.parse(message);
    } catch (e) {
      console.error('Invalid JSON message:', e);
      return;
    }

    const { type, room, payload } = data;

    switch (type) {
      case 'create-room': {
        const roomCode = room || Math.random().toString(36).substring(2, 7).toUpperCase();
        currentRoom = roomCode;
        isHost = true;
        rooms.set(roomCode, { host: ws, peers: new Set() });
        ws.send(JSON.stringify({ type: 'room-created', room: roomCode }));
        console.log(`[Room ${roomCode}] Host created room.`);
        break;
      }

      case 'join-room': {
        const targetRoom = room ? room.toUpperCase() : null;
        if (!targetRoom || !rooms.has(targetRoom)) {
          ws.send(JSON.stringify({ type: 'error', message: 'Room not found' }));
          return;
        }

        currentRoom = targetRoom;
        isHost = false;
        const roomData = rooms.get(targetRoom);
        roomData.peers.add(ws);

        ws.send(JSON.stringify({ type: 'joined-room', room: targetRoom }));
        console.log(`[Room ${targetRoom}] Listener peer connected.`);

        // Notify host that a new listener joined
        if (roomData.host && roomData.host.readyState === WebSocket.OPEN) {
          roomData.host.send(JSON.stringify({
            type: 'peer-joined',
            peerId: data.peerId || 'listener'
          }));
        }
        break;
      }

      case 'signal': {
        // Forward WebRTC signals (offer, answer, ice-candidate)
        if (!currentRoom || !rooms.has(currentRoom)) return;
        const roomData = rooms.get(currentRoom);

        if (isHost) {
          // Broadcast signal to all listeners in room
          for (const peer of roomData.peers) {
            if (peer.readyState === WebSocket.OPEN) {
              peer.send(JSON.stringify({ type: 'signal', payload }));
            }
          }
        } else {
          // Forward listener signal back to host
          if (roomData.host && roomData.host.readyState === WebSocket.OPEN) {
            roomData.host.send(JSON.stringify({ type: 'signal', payload }));
          }
        }
        break;
      }

      case 'sync-clock': {
        // High precision ping for network jitter & RTT calculation
        ws.send(JSON.stringify({
          type: 'sync-clock-reply',
          clientTime: payload.clientTime,
          serverTime: Date.now()
        }));
        break;
      }

      default:
        console.log('Unknown message type:', type);
    }
  });

  ws.on('close', () => {
    if (currentRoom && rooms.has(currentRoom)) {
      const roomData = rooms.get(currentRoom);
      if (isHost) {
        console.log(`[Room ${currentRoom}] Host disconnected. Closing room.`);
        for (const peer of roomData.peers) {
          if (peer.readyState === WebSocket.OPEN) {
            peer.send(JSON.stringify({ type: 'host-left', message: 'Host has closed the broadcast.' }));
          }
        }
        rooms.delete(currentRoom);
      } else {
        console.log(`[Room ${currentRoom}] Listener disconnected.`);
        roomData.peers.delete(ws);
        if (roomData.host && roomData.host.readyState === WebSocket.OPEN) {
          roomData.host.send(JSON.stringify({ type: 'peer-left' }));
        }
      }
    }
  });
});

// Fallback route for SPA router
app.get('*', (req, res) => {
  const indexPath = path.join(clientDist, 'index.html');
  if (require('fs').existsSync(indexPath)) {
    res.sendFile(indexPath);
  } else {
    res.send(`
      <!DOCTYPE html>
      <html>
        <head><title>EarSync Relay Server</title></head>
        <body style="font-family: system-ui, sans-serif; padding: 40px; text-align: center; background: #0f172a; color: #f8fafc;">
          <h1>🎧 EarSync Signaling Relay Running</h1>
          <p>WebSocket server active on port ${PORT}. Run client with <code>npm run dev</code> inside <code>/client</code> or build it with <code>npm run build</code>.</p>
        </body>
      </html>
    `);
  }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  const ips = getLocalIpAddresses();
  console.log('\n======================================================');
  console.log('🎧  EarSync: Zero-Latency Audio Sharing Relay Server');
  console.log('======================================================');
  console.log(`Server listening locally:   http://localhost:${PORT}`);
  
  if (ips.length > 0) {
    console.log('\nAvailable on your Local Wi-Fi / Mobile Hotspot:');
    ips.forEach(({ interface: iface, address }) => {
      console.log(`  👉  http://${address}:${PORT}  (${iface})`);
    });
    
    // Try rendering QR code in terminal for quickest access
    try {
      const qrcode = require('qrcode-terminal');
      console.log('\nScan to open on friend’s phone:');
      qrcode.generate(`http://${ips[0].address}:${PORT}`, { small: true });
    } catch (e) {
      // qrcode-terminal optional fallback
    }
  } else {
    console.log('\n(No local network interfaces found. Connect to Wi-Fi or turn on Mobile Hotspot)');
  }
  console.log('======================================================\n');
});
