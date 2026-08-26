/**
 * AgenticDroid Remote Agent Server
 *
 * A lightweight companion server for your development machine that allows
 * AgenticDroid to connect over LAN/VPN without SSH.
 *
 * Features:
 * - mDNS Auto-discovery (_agenticdroid._tcp)
 * - REST API for high-speed file system access
 * - WebSocket for PTY-backed terminal sessions
 */

const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const pty = require('node-pty');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { Bonjour } = require('bonjour-service');

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 41338;
const WORKSPACE_ROOT = process.env.WORKSPACE_ROOT || os.homedir();

// This server exposes unrestricted filesystem read/write and arbitrary command execution
// to anyone who can reach it - by default that's the whole LAN. Require a shared-secret
// bearer token on every request unless the operator explicitly opts out.
const crypto = require('crypto');
const REQUIRE_AUTH = process.env.AGENTICDROID_NO_AUTH !== '1';
const AUTH_TOKEN = process.env.AGENTICDROID_TOKEN || crypto.randomBytes(24).toString('base64url');

function isValidToken(provided) {
    if (!provided || provided.length !== AUTH_TOKEN.length) return false;
    return crypto.timingSafeEqual(Buffer.from(provided), Buffer.from(AUTH_TOKEN));
}

function checkAuth(req, res, next) {
    if (!REQUIRE_AUTH) return next();
    const header = req.headers['authorization'] || '';
    const provided = header.startsWith('Bearer ') ? header.slice(7) : null;
    if (isValidToken(provided)) return next();
    res.status(401).json({ error: 'Unauthorized: missing or invalid pairing token' });
}

app.use('/api', checkAuth);

// --- Filesystem API ---

app.get('/api/files/list', (req, res) => {
    const dir = req.query.path || WORKSPACE_ROOT;
    try {
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        const result = entries.map(entry => ({
            name: entry.name,
            path: path.join(dir, entry.name),
            isDirectory: entry.isDirectory(),
            size: entry.isDirectory() ? 0 : fs.statSync(path.join(dir, entry.name)).size
        })).sort((a, b) => b.isDirectory - a.isDirectory || a.name.localeCompare(b.name));
        res.json(result);
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.get('/api/files/read', (req, res) => {
    const filePath = req.query.path;
    try {
        const content = fs.readFileSync(filePath, 'utf8');
        res.send(content);
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.post('/api/files/write', (req, res) => {
    const { path: filePath, content } = req.body;
    try {
        fs.writeFileSync(filePath, content, 'utf8');
        res.json({ status: 'success' });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.get('/api/files/exists', (req, res) => {
    const filePath = req.query.path;
    try {
        res.json({ exists: fs.existsSync(filePath) });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.post('/api/exec', (req, res) => {
    const { command, cwd, env } = req.body;
    // Simple one-shot exec for non-interactive tools
    const { exec } = require('child_process');
    exec(command, { cwd: cwd || WORKSPACE_ROOT, env: { ...process.env, ...env } }, (error, stdout, stderr) => {
        res.json({
            exitCode: error ? error.code : 0,
            stdout,
            stderr
        });
    });
});

// --- WebSocket PTY Terminal ---

const server = http.createServer(app);
const wss = new WebSocketServer({
    server,
    path: '/terminal',
    verifyClient: (info, callback) => {
        if (!REQUIRE_AUTH) return callback(true);
        const provided = (info.req.headers['authorization'] || '').replace(/^Bearer /, '');
        if (isValidToken(provided)) return callback(true);
        callback(false, 401, 'Unauthorized');
    }
});

wss.on('connection', (ws, req) => {
    const params = new URLSearchParams(req.url.split('?')[1]);
    const cwd = params.get('cwd') || WORKSPACE_ROOT;
    const shell = os.platform() === 'win32' ? 'powershell.exe' : 'bash';

    const ptyProcess = pty.spawn(shell, [], {
        name: 'xterm-256color',
        cols: 80,
        rows: 24,
        cwd: cwd,
        env: process.env
    });

    ptyProcess.onData(data => ws.send(data));
    ws.on('message', message => ptyProcess.write(message.toString()));

    ws.on('close', () => {
        ptyProcess.kill();
    });
});

// --- Start Server & Discovery ---

server.listen(PORT, '0.0.0.0', () => {
    console.log(`AgenticDroid Remote Server running on port ${PORT}`);
    console.log(`Workspace root: ${WORKSPACE_ROOT}`);
    if (REQUIRE_AUTH) {
        console.log(`Pairing token (enter this in AgenticDroid when adding this server): ${AUTH_TOKEN}`);
    } else {
        console.log('WARNING: AGENTICDROID_NO_AUTH=1 - this server accepts unauthenticated requests from anyone on the network.');
    }

    const bonjour = new Bonjour();
    bonjour.publish({
        name: `AgenticDroid-${os.hostname()}`,
        type: 'agenticdroid',
        protocol: 'tcp',
        port: PORT,
        txt: {
            user: os.userInfo().username,
            os: os.platform(),
            version: '1.0.0'
        }
    });
    console.log('mDNS discovery active (_agenticdroid._tcp)');
});
