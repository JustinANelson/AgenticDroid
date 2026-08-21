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
const wss = new WebSocketServer({ server, path: '/terminal' });

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
