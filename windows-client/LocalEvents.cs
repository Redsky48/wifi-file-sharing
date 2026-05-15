using System;
using System.Threading;

namespace WiFiShareTray;

public sealed record EventEnvelope(string Type, object? Data, long Ts, long Seq);

/// <summary>
/// Process-wide event broadcaster for the tray. Anything interesting
/// (device discovered, file received, state changed, ...) calls Push,
/// and all subscribers (WebSocket clients, SSE clients, in-process
/// listeners) receive it.
///
/// Single static instance — there's only one tray per process and one
/// PC per tray, so a global broadcaster is the right fit (vs. injected
/// instance).
/// </summary>
public static class LocalEvents
{
    private static long _seq;

    /// <summary>Fires for every emitted event. Listeners must be fast or async — handlers run on the emitting thread.</summary>
    public static event Action<EventEnvelope>? Emit;

    public static void Push(string type, object? data)
    {
        var ev = new EventEnvelope(
            Type: type,
            Data: data,
            Ts: DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Seq: Interlocked.Increment(ref _seq));
        try { Emit?.Invoke(ev); } catch { /* a buggy listener must not crash the producer */ }
    }
}
