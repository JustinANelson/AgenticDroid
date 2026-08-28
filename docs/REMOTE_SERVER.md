# LAN companion server

The companion in `tools/remote-agent-server.js` lets AgenticDroid edit files and execute shells
on another computer. Anyone with its pairing token has remote-code-execution capability as the
operating-system account running the server.

## Run it

Install Node.js 20 or newer, then from `tools/` run:

```sh
npm ci
WORKSPACE_ROOT=/absolute/path/to/workspaces AGENTICDROID_TOKEN='a-long-random-token' npm start
```

PowerShell equivalent:

```powershell
$env:WORKSPACE_ROOT = 'C:\path\to\workspaces'
$env:AGENTICDROID_TOKEN = 'a-long-random-token'
npm start
```

If `AGENTICDROID_TOKEN` is omitted, the server generates a random token and prints it at startup.
Enter that token when adding the LAN environment in AgenticDroid. `PORT` defaults to `41338`.

## Security model

- Bearer authentication is required by default for REST and WebSocket requests.
- Structured filesystem operations and requested working directories are canonically contained
  below `WORKSPACE_ROOT`, including checks against symlink escapes.
- Commands are otherwise unrestricted and inherit the server process's environment and operating-
  system permissions.
- Traffic uses plain HTTP and WebSocket. Authentication does not provide confidentiality.
- mDNS advertises the service and local username on the attached network.

`WORKSPACE_ROOT` is not a sandbox for shell commands: an authenticated command can use absolute
paths or change directories. Use a dedicated, unprivileged operating-system account and a narrowly scoped workspace root.
Keep secrets out of the server process environment when they are not needed. Bind access with a
host firewall and use only a trusted LAN, VPN, or encrypted tunnel. Never forward port `41338`
directly from the public internet.

Setting `AGENTICDROID_NO_AUTH=1` disables authentication and is intended only for isolated local
testing. Do not use it on a shared network.
