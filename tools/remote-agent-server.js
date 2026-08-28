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
const WORKSPACE_ROOT = path.resolve(process.env.WORKSPACE_ROOT || os.homedir());

if (!fs.existsSync(WORKSPACE_ROOT) || !fs.statSync(WORKSPACE_ROOT).isDirectory()) {
    throw new Error(`WORKSPACE_ROOT must be an existing directory: ${WORKSPACE_ROOT}`);
}

const CANONICAL_WORKSPACE_ROOT = fs.realpathSync(WORKSPACE_ROOT);

function isInsideWorkspace(candidate) {
    const relative = path.relative(CANONICAL_WORKSPACE_ROOT, candidate);
    return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}

/**
 * Resolves a client path below WORKSPACE_ROOT and rejects lexical and symlink escapes.
 * For a path that does not exist yet, its nearest existing ancestor is canonicalized.
 */
function resolveWorkspacePath(candidate, defaultPath = CANONICAL_WORKSPACE_ROOT) {
    const supplied = candidate || defaultPath;
    const resolved = path.resolve(CANONICAL_WORKSPACE_ROOT, supplied);
    if (!isInsideWorkspace(resolved)) {
        const error = new Error('Path is outside WORKSPACE_ROOT');
        error.statusCode = 403;
        throw error;
    }

    let existing = resolved;
    while (!fs.existsSync(existing)) {
        const parent = path.dirname(existing);
        if (parent === existing) break;
        existing = parent;
    }
    const canonicalAncestor = fs.realpathSync(existing);
    if (!isInsideWorkspace(canonicalAncestor)) {
        const error = new Error('Path resolves outside WORKSPACE_ROOT');
        error.statusCode = 403;
        throw error;
    }

    if (fs.existsSync(resolved)) {
        const canonical = fs.realpathSync(resolved);
        if (!isInsideWorkspace(canonical)) {
            const error = new Error('Path resolves outside WORKSPACE_ROOT');
            error.statusCode = 403;
            throw error;
        }
        return canonical;
    }
    return resolved;
}

function sendError(res, error) {
    const status = Number.isInteger(error.statusCode) ? error.statusCode : 500;
    res.status(status).json({ error: error.message });
}

// This server exposes filesystem read/write below WORKSPACE_ROOT and arbitrary command
// execution to anyone who can reach it - by default that's the whole LAN. Require a
// shared-secret bearer token on every request unless the operator explicitly opts out.
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
    try {
        const dir = resolveWorkspacePath(req.query.path);
        const entries = fs.readdirSync(dir, { withFileTypes: true });
        const result = entries.map(entry => ({
            name: entry.name,
            path: path.join(dir, entry.name),
            isDirectory: entry.isDirectory(),
            size: entry.isDirectory() ? 0 : fs.statSync(path.join(dir, entry.name)).size
        })).sort((a, b) => b.isDirectory - a.isDirectory || a.name.localeCompare(b.name));
        res.json(result);
    } catch (e) {
        sendError(res, e);
    }
});

app.get('/api/files/read', (req, res) => {
    try {
        const filePath = resolveWorkspacePath(req.query.path);
        const content = fs.readFileSync(filePath, 'utf8');
        res.send(content);
    } catch (e) {
        sendError(res, e);
    }
});

app.post('/api/files/write', (req, res) => {
    try {
        const filePath = resolveWorkspacePath(req.body.path);
        const { content } = req.body;
        fs.writeFileSync(filePath, content, 'utf8');
        res.json({ status: 'success' });
    } catch (e) {
        sendError(res, e);
    }
});

app.get('/api/files/exists', (req, res) => {
    try {
        const filePath = resolveWorkspacePath(req.query.path);
        res.json({ exists: fs.existsSync(filePath) });
    } catch (e) {
        sendError(res, e);
    }
});

// /api/files/read+write round-trip through UTF-8 text (fine for source files, but silently
// corrupts anything else - a built APK, an image). These two routes move raw bytes instead,
// for binary transfers like LANExecutionEnvironment's downloadFile/uploadStream.
app.get('/api/files/download', (req, res) => {
    try {
        const filePath = resolveWorkspacePath(req.query.path);
        if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
            return res.status(404).json({ error: 'File not found' });
        }
        res.setHeader('Content-Type', 'application/octet-stream');
        res.setHeader('Content-Length', fs.statSync(filePath).size);
        fs.createReadStream(filePath)
            .on('error', (e) => res.status(500).end(e.message))
            .pipe(res);
    } catch (e) {
        sendError(res, e);
    }
});

app.post('/api/files/upload', express.raw({ type: '*/*', limit: '1024mb' }), (req, res) => {
    try {
        const filePath = resolveWorkspacePath(req.query.path);
        fs.mkdirSync(path.dirname(filePath), { recursive: true });
        fs.writeFileSync(filePath, req.body);
        res.json({ status: 'success' });
    } catch (e) {
        sendError(res, e);
    }
});

app.post('/api/exec', (req, res) => {
    try {
        const { command, cwd, env } = req.body;
        const commandCwd = resolveWorkspacePath(cwd);
        // Simple one-shot exec for non-interactive tools. The cwd is contained, but the
        // command itself is intentionally arbitrary and can access anything available to
        // this OS account; authentication is the actual security boundary.
        const { exec } = require('child_process');
        exec(command, { cwd: commandCwd, env: { ...process.env, ...env } }, (error, stdout, stderr) => {
            res.json({
                exitCode: error ? error.code : 0,
                stdout,
                stderr
            });
        });
    } catch (e) {
        sendError(res, e);
    }
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
    let ptyProcess;
    try {
        const params = new URL(req.url, 'http://localhost').searchParams;
        const cwd = resolveWorkspacePath(params.get('cwd'));
        const shell = os.platform() === 'win32' ? 'powershell.exe' : 'bash';

        ptyProcess = pty.spawn(shell, [], {
            name: 'xterm-256color',
            cols: 80,
            rows: 24,
            cwd,
            env: process.env
        });
    } catch (e) {
        ws.close(1008, e.message);
        return;
    }

    ptyProcess.onData(data => ws.send(data));
    ws.on('message', message => ptyProcess.write(message.toString()));

    ws.on('close', () => {
        ptyProcess.kill();
    });
});

// --- Start Server & Discovery ---

server.listen(PORT, '0.0.0.0', () => {
    console.log(`AgenticDroid Remote Server running on port ${PORT}`);
    console.log(`Workspace root: ${CANONICAL_WORKSPACE_ROOT}`);
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
