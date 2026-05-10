# WiFi Share — Windows tray companion

Tiny Windows tray app that auto-discovers your phone's WiFi-Share server over mDNS, mounts it as a network drive, and unmounts when the phone goes away.

## Build

Requires **.NET 8 SDK** ([download](https://dotnet.microsoft.com/download/dotnet/8.0)).

```cmd
cd windows-client
dotnet publish -c Release
```

Output: `bin\Release\net8.0-windows\win-x64\publish\WiFiShareTray.exe` (~3 MB, framework-dependent).

For a self-contained binary that runs on machines without .NET installed (~70 MB):

```cmd
dotnet publish -c Release -p:SelfContained=true
```

## Run

Double-click `WiFiShareTray.exe`. A circle icon appears in the tray:

| Color | Meaning |
|-------|---------|
| 🟦 blue   | scanning for phones |
| 🟨 yellow | connecting |
| 🟩 green  | mounted (`Z:` or another free letter) |
| ⬜ gray   | manually disconnected |

Right-click for menu:
- **Devices** — pick which phone to connect to (auto-connects to the first one found)
- **Open in Explorer** — opens the mounted drive
- **Disconnect** — `net use /delete`
- **Auto-connect when found** — toggle

## How it works

```
Phone                              PC (this app)
─────                              ─────────────
mDNS advertise                     mDNS browse
  _wifishare._tcp ────────►          (Makaretu.Dns)
  _webdav._tcp                          │
                                        ▼
                                   net use Z: http://phone:8080/dav
                                        │
                                        ▼  (Windows WebClient mini-redirector)
WebDAV server   ◄──────────────────  Z:\ in Explorer
  /dav/PROPFIND
  /dav/GET, PUT, DELETE
                                        │
                                        ▼  (background ping every 4s)
HTTP /api/files probe                3 misses → unmount
```

## Requirements

- Windows 10/11 (mDNS support is built-in since Windows 10 1803)
- The **WebClient** service must be running. Windows enables it on demand on first `net use http://...`, but on Pro/Enterprise it might be disabled. To force-enable:
  ```cmd
  sc config WebClient start= auto
  net start WebClient
  ```

## Known limits

- Windows WebDAV client has a default **50 MB request size limit**. Bump it for big files (`HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\FileSizeLimitInBytes` → `0xFFFFFFFF`, requires reboot).
- The WebDAV server on the phone is **flat** — no subdirectories yet. The mounted drive shows all files in the picked Android folder as one list.
- No authentication. Use only on trusted WiFi networks.

## Logs / troubleshooting

- If the drive doesn't mount: open a `cmd` prompt and run the same `net use` command manually — Windows will show the actual error code (`1920` = WebClient service stopped, `67` = network name not found, `224` = bad protocol).
- If mDNS finds nothing: confirm the phone and PC are on the same subnet, and that no firewall is blocking UDP 5353. Windows Defender Firewall sometimes blocks the first multicast — answer "Allow" when prompted.
