using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Threading;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// Listens on a named pipe so that secondary process launches (e.g. the
/// Send-To shortcut firing the .exe with file path arguments) can hand
/// off their args to the already-running tray instance.
/// </summary>
internal sealed class IpcServer : IDisposable
{
    public const string PipeName = "WiFiShareTray.Ipc.v1";

    public event Action<string[]>? PathsReceived;

    private CancellationTokenSource? _cts;

    public void Start()
    {
        Stop();
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
    }

    private async Task Loop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var server = new NamedPipeServerStream(
                    PipeName, PipeDirection.In, NamedPipeServerStream.MaxAllowedServerInstances,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync(ct);
                using var reader = new StreamReader(server);
                var paths = new List<string>();
                string? line;
                while ((line = await reader.ReadLineAsync(ct)) != null)
                {
                    if (!string.IsNullOrWhiteSpace(line)) paths.Add(line.Trim());
                }
                if (paths.Count > 0)
                {
                    try { PathsReceived?.Invoke(paths.ToArray()); } catch { /* don't kill the loop */ }
                }
            }
            catch (OperationCanceledException) { return; }
            catch
            {
                // Pipe disconnected mid-read or similar — keep listening.
            }
        }
    }

    public void Dispose() => Stop();
}

internal static class IpcClient
{
    public static bool Send(string[] paths, int timeoutMs = 2000)
    {
        if (paths.Length == 0) return false;
        try
        {
            using var client = new NamedPipeClientStream(".", IpcServer.PipeName, PipeDirection.Out);
            client.Connect(timeoutMs);
            using var writer = new StreamWriter(client) { AutoFlush = true };
            foreach (var p in paths) writer.WriteLine(p);
            return true;
        }
        catch
        {
            return false;
        }
    }
}
