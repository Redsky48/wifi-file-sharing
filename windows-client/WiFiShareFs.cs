using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text.Json;
using Fsp;
using FspFileInfo = Fsp.Interop.FileInfo;
using FspVolumeInfo = Fsp.Interop.VolumeInfo;

namespace WiFiShareTray;

/// <summary>
/// User-space WinFSP filesystem that proxies all I/O to the phone's HTTP
/// API. The mounted drive looks like a real local disk to Windows, which
/// avoids the WebClient mini-redirector quirks (file size cap, MPC-HC's
/// "File not found", slow PROPFIND caches).
///
/// The shared folder is presented as a flat root collection — same model
/// as the WebDAV side. Reads use HTTP Range; writes are buffered locally
/// and flushed to the server with a single PUT on Cleanup.
/// </summary>
internal sealed class WiFiShareFs : FileSystemBase
{
    // STATUS_* and FILE_DIRECTORY_FILE come from FileSystemBase.

    private const uint FileAttributes_Directory = 0x10;
    private const uint FileAttributes_Normal = 0x80;
    private const uint CLEANUP_DELETE = 0x00000001;

    private static readonly TimeSpan CacheTtl = TimeSpan.FromSeconds(2);

    private readonly Uri _baseUrl;
    private readonly string _label;
    private readonly HttpClient _http;
    private readonly object _cacheLock = new();
    private List<FileMeta> _listing = new();
    private DateTime _listingAge = DateTime.MinValue;

    public WiFiShareFs(string baseUrl, string label, string? password)
    {
        _baseUrl = new Uri(baseUrl.TrimEnd('/') + "/");
        _label = string.IsNullOrWhiteSpace(label) ? "WiFi Share" : label;
        _http = AuthHttp.Build(_baseUrl, password, TimeSpan.FromSeconds(30));
    }

    public override int Init(object Host)
    {
        var host = (FileSystemHost)Host;
        host.SectorSize = 4096;
        host.SectorsPerAllocationUnit = 1;
        host.MaxComponentLength = 255;
        host.FileInfoTimeout = 1000;
        host.CaseSensitiveSearch = false;
        host.CasePreservedNames = true;
        host.UnicodeOnDisk = true;
        host.PostCleanupWhenModifiedOnly = true;
        host.VolumeCreationTime = (ulong)DateTime.UtcNow.ToFileTimeUtc();
        host.VolumeSerialNumber = (uint)_baseUrl.GetHashCode();
        host.PassQueryDirectoryPattern = false;
        host.FileSystemName = "WiFiShare";
        return STATUS_SUCCESS;
    }

    public override int GetVolumeInfo(out FspVolumeInfo VolumeInfo)
    {
        VolumeInfo = default;
        // Pretend 1 TB / 256 GB free. Real values would need a server-side
        // statfs endpoint; the WebDAV quota fields aren't reachable here.
        VolumeInfo.TotalSize = 1L * 1024 * 1024 * 1024 * 1024;
        VolumeInfo.FreeSize = 256L * 1024 * 1024 * 1024;
        VolumeInfo.SetVolumeLabel(_label);
        return STATUS_SUCCESS;
    }

    public override int GetSecurityByName(string FileName, out uint FileAttributes,
        ref byte[] SecurityDescriptor)
    {
        FileAttributes = 0;
        if (IsRoot(FileName))
        {
            FileAttributes = FileAttributes_Directory;
            return STATUS_SUCCESS;
        }
        var name = NormalizeName(FileName);
        var meta = LookupFile(name);
        if (meta == null) return STATUS_OBJECT_NAME_NOT_FOUND;
        FileAttributes = FileAttributes_Normal;
        return STATUS_SUCCESS;
    }

    public override int Open(string FileName, uint CreateOptions, uint GrantedAccess,
        out object FileNode, out object FileDesc, out FspFileInfo FileInfo, out string NormalizedName)
    {
        FileNode = null!;
        FileDesc = null!;
        FileInfo = default;
        NormalizedName = null!;

        if (IsRoot(FileName))
        {
            var rootHandle = new Handle { Node = new Node { IsDirectory = true } };
            FileNode = rootHandle.Node;
            FileDesc = rootHandle;
            FileInfo.FileAttributes = FileAttributes_Directory;
            NormalizedName = "\\";
            return STATUS_SUCCESS;
        }

        var name = NormalizeName(FileName);
        var meta = LookupFile(name);
        if (meta == null) return STATUS_OBJECT_NAME_NOT_FOUND;

        var node = new Node
        {
            Name = meta.Name,
            Size = meta.Size,
            Modified = meta.Modified,
        };
        FileNode = node;
        FileDesc = new Handle { Node = node };
        PopulateFileInfo(meta, ref FileInfo);
        NormalizedName = "\\" + meta.Name;
        return STATUS_SUCCESS;
    }

    public override int Create(string FileName, uint CreateOptions, uint GrantedAccess,
        uint FileAttributes, byte[] SecurityDescriptor, ulong AllocationSize,
        out object FileNode, out object FileDesc, out FspFileInfo FileInfo, out string NormalizedName)
    {
        FileNode = null!;
        FileDesc = null!;
        FileInfo = default;
        NormalizedName = null!;

        if (IsRoot(FileName)) return STATUS_OBJECT_NAME_COLLISION;
        if ((CreateOptions & FILE_DIRECTORY_FILE) != 0) return STATUS_NOT_IMPLEMENTED;

        var name = NormalizeName(FileName);
        if (string.IsNullOrEmpty(name)) return STATUS_OBJECT_NAME_NOT_FOUND;
        if (LookupFile(name) != null) return STATUS_OBJECT_NAME_COLLISION;

        var node = new Node
        {
            Name = name,
            Size = 0,
            Modified = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        var handle = new Handle
        {
            Node = node,
            WriteBuffer = new MemoryStream(),
            Dirty = true,
            CreatedNew = true,
        };
        FileNode = node;
        FileDesc = handle;

        FileInfo.FileAttributes = FileAttributes_Normal;
        FileInfo.FileSize = 0;
        FileInfo.AllocationSize = 0;
        var nowFt = (ulong)DateTime.UtcNow.ToFileTimeUtc();
        FileInfo.CreationTime = nowFt;
        FileInfo.LastWriteTime = nowFt;
        FileInfo.LastAccessTime = nowFt;
        FileInfo.ChangeTime = nowFt;
        NormalizedName = "\\" + name;
        return STATUS_SUCCESS;
    }

    public override int Read(object FileNode, object FileDesc, IntPtr Buffer,
        ulong Offset, uint Length, out uint BytesTransferred)
    {
        BytesTransferred = 0;
        if (FileDesc is not Handle h || h.Node.IsDirectory) return STATUS_INVALID_DEVICE_REQUEST;

        // If we've been writing locally, serve reads from the buffer
        if (h.WriteBuffer != null && h.Dirty)
        {
            var buf = h.WriteBuffer.GetBuffer();
            var bufLen = h.WriteBuffer.Length;
            if ((long)Offset >= bufLen) return STATUS_END_OF_FILE;
            var canRead = (int)Math.Min((long)Length, bufLen - (long)Offset);
            Marshal.Copy(buf, (int)Offset, Buffer, canRead);
            BytesTransferred = (uint)canRead;
            return STATUS_SUCCESS;
        }

        if ((long)Offset >= h.Node.Size) return STATUS_END_OF_FILE;
        var maxRead = Math.Min((long)Length, h.Node.Size - (long)Offset);
        if (maxRead <= 0) return STATUS_END_OF_FILE;

        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get,
                "dav/" + Uri.EscapeDataString(h.Node.Name));
            req.Headers.Range = new RangeHeaderValue((long)Offset, (long)Offset + maxRead - 1);
            using var resp = _http.Send(req, HttpCompletionOption.ResponseHeadersRead);
            if (!resp.IsSuccessStatusCode) return STATUS_INVALID_DEVICE_REQUEST;

            using var stream = resp.Content.ReadAsStream();
            var data = new byte[maxRead];
            int total = 0;
            while (total < maxRead)
            {
                var n = stream.Read(data, total, (int)(maxRead - total));
                if (n <= 0) break;
                total += n;
            }
            Marshal.Copy(data, 0, Buffer, total);
            BytesTransferred = (uint)total;
            return STATUS_SUCCESS;
        }
        catch
        {
            return STATUS_INVALID_DEVICE_REQUEST;
        }
    }

    public override int Write(object FileNode, object FileDesc, IntPtr Buffer,
        ulong Offset, uint Length, bool WriteToEndOfFile, bool ConstrainedIo,
        out uint BytesTransferred, out FspFileInfo FileInfo)
    {
        BytesTransferred = 0;
        FileInfo = default;
        if (FileDesc is not Handle h || h.Node.IsDirectory) return STATUS_INVALID_DEVICE_REQUEST;

        if (h.WriteBuffer == null)
        {
            h.WriteBuffer = new MemoryStream();
            if (!h.CreatedNew && h.Node.Size > 0)
            {
                if (!DownloadIntoBuffer(h)) return STATUS_INVALID_DEVICE_REQUEST;
            }
        }

        var data = new byte[Length];
        Marshal.Copy(Buffer, data, 0, (int)Length);
        var writeOffset = WriteToEndOfFile ? h.WriteBuffer.Length : (long)Offset;
        h.WriteBuffer.Position = writeOffset;
        h.WriteBuffer.Write(data, 0, (int)Length);
        h.Dirty = true;
        BytesTransferred = Length;
        h.Node.Size = h.WriteBuffer.Length;

        FileInfo.FileAttributes = FileAttributes_Normal;
        FileInfo.FileSize = (ulong)h.WriteBuffer.Length;
        FileInfo.AllocationSize = FileInfo.FileSize;
        var nowFt = (ulong)DateTime.UtcNow.ToFileTimeUtc();
        FileInfo.LastWriteTime = nowFt;
        FileInfo.ChangeTime = nowFt;
        FileInfo.CreationTime = nowFt;
        FileInfo.LastAccessTime = nowFt;
        return STATUS_SUCCESS;
    }

    public override int Flush(object FileNode, object FileDesc, out FspFileInfo FileInfo)
    {
        FileInfo = default;
        return STATUS_SUCCESS;
    }

    public override int GetFileInfo(object FileNode, object FileDesc, out FspFileInfo FileInfo)
    {
        FileInfo = default;
        if (FileDesc is not Handle h) return STATUS_INVALID_DEVICE_REQUEST;
        if (h.Node.IsDirectory)
        {
            FileInfo.FileAttributes = FileAttributes_Directory;
            return STATUS_SUCCESS;
        }
        var size = h.WriteBuffer?.Length ?? h.Node.Size;
        FileInfo.FileAttributes = FileAttributes_Normal;
        FileInfo.FileSize = (ulong)size;
        FileInfo.AllocationSize = (ulong)size;
        var ft = ToFileTime(h.Node.Modified);
        FileInfo.LastWriteTime = ft;
        FileInfo.CreationTime = ft;
        FileInfo.LastAccessTime = ft;
        FileInfo.ChangeTime = ft;
        return STATUS_SUCCESS;
    }

    public override int SetFileSize(object FileNode, object FileDesc, ulong NewSize,
        bool SetAllocationSize, out FspFileInfo FileInfo)
    {
        FileInfo = default;
        if (FileDesc is not Handle h || h.Node.IsDirectory) return STATUS_INVALID_DEVICE_REQUEST;

        if (h.WriteBuffer == null)
        {
            h.WriteBuffer = new MemoryStream();
            if (!h.CreatedNew && h.Node.Size > 0 && !DownloadIntoBuffer(h))
                return STATUS_INVALID_DEVICE_REQUEST;
        }
        h.WriteBuffer.SetLength((long)NewSize);
        h.Dirty = true;
        h.Node.Size = (long)NewSize;
        return GetFileInfo(FileNode, FileDesc, out FileInfo);
    }

    public override int CanDelete(object FileNode, object FileDesc, string FileName)
    {
        if (FileDesc is Handle h && h.Node.IsDirectory) return STATUS_ACCESS_DENIED;
        return STATUS_SUCCESS;
    }

    public override int Rename(object FileNode, object FileDesc,
        string FileName, string NewFileName, bool ReplaceIfExists)
    {
        if (FileDesc is not Handle h || h.Node.IsDirectory) return STATUS_INVALID_DEVICE_REQUEST;
        var newName = NormalizeName(NewFileName);
        if (string.IsNullOrEmpty(newName)) return STATUS_OBJECT_NAME_NOT_FOUND;

        try
        {
            var req = new HttpRequestMessage(new HttpMethod("MOVE"),
                "dav/" + Uri.EscapeDataString(h.Node.Name));
            req.Headers.Add("Destination", _baseUrl.AbsoluteUri.TrimEnd('/') +
                "/dav/" + Uri.EscapeDataString(newName));
            using var resp = _http.Send(req);
            if (!resp.IsSuccessStatusCode) return STATUS_INVALID_DEVICE_REQUEST;
            h.Node.Name = newName;
            InvalidateCache();
            return STATUS_SUCCESS;
        }
        catch { return STATUS_INVALID_DEVICE_REQUEST; }
    }

    public override void Cleanup(object FileNode, object FileDesc, string FileName, uint Flags)
    {
        if (FileDesc is not Handle h) return;

        if ((Flags & CLEANUP_DELETE) != 0 && !h.Node.IsDirectory)
        {
            try
            {
                using var resp = _http.Send(new HttpRequestMessage(HttpMethod.Delete,
                    "dav/" + Uri.EscapeDataString(h.Node.Name)));
            }
            catch { /* best-effort */ }
            h.Dirty = false;
            h.WriteBuffer?.Dispose();
            h.WriteBuffer = null;
            InvalidateCache();
            return;
        }

        if (h.Dirty && h.WriteBuffer != null)
        {
            try
            {
                var bytes = h.WriteBuffer.ToArray();
                using var content = new ByteArrayContent(bytes);
                content.Headers.ContentLength = bytes.Length;
                using var req = new HttpRequestMessage(HttpMethod.Put,
                    "dav/" + Uri.EscapeDataString(h.Node.Name))
                {
                    Content = content,
                };
                using var resp = _http.Send(req);
            }
            catch { /* best-effort */ }
            h.Dirty = false;
            InvalidateCache();
        }
    }

    public override void Close(object FileNode, object FileDesc)
    {
        if (FileDesc is Handle h)
        {
            h.WriteBuffer?.Dispose();
            h.WriteBuffer = null;
        }
    }

    public override bool ReadDirectoryEntry(object FileNode, object FileDesc,
        string Pattern, string Marker, ref object Context,
        out string FileName, out FspFileInfo FileInfo)
    {
        FileName = null!;
        FileInfo = default;
        if (FileDesc is not Handle h || !h.Node.IsDirectory) return false;

        // First call: build sorted list, advance past Marker, store iterator.
        var iter = Context as IEnumerator<FileMeta>;
        if (iter == null)
        {
            var items = ListFiles().OrderBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList();
            if (!string.IsNullOrEmpty(Marker))
            {
                var idx = items.FindIndex(f =>
                    string.Equals(f.Name, Marker, StringComparison.OrdinalIgnoreCase));
                if (idx >= 0) items = items.Skip(idx + 1).ToList();
            }
            iter = items.GetEnumerator();
            Context = iter;
        }

        if (!iter.MoveNext()) return false;
        var item = iter.Current;
        FileName = item.Name;
        FileInfo.FileAttributes = FileAttributes_Normal;
        FileInfo.FileSize = (ulong)item.Size;
        FileInfo.AllocationSize = (ulong)item.Size;
        var ft = ToFileTime(item.Modified);
        FileInfo.LastWriteTime = ft;
        FileInfo.CreationTime = ft;
        FileInfo.LastAccessTime = ft;
        FileInfo.ChangeTime = ft;
        return true;
    }

    // -------- helpers ---------------------------------------------------

    private static bool IsRoot(string fileName) => fileName == "" || fileName == "\\";
    private static string NormalizeName(string fileName) => fileName.TrimStart('\\').Replace('\\', '/');

    private FileMeta? LookupFile(string name) =>
        ListFiles().FirstOrDefault(f =>
            string.Equals(f.Name, name, StringComparison.OrdinalIgnoreCase));

    private List<FileMeta> ListFiles()
    {
        lock (_cacheLock)
        {
            if (_listing.Count > 0 && DateTime.UtcNow - _listingAge < CacheTtl)
                return _listing;
        }

        var fresh = new List<FileMeta>();
        try
        {
            using var resp = _http.Send(new HttpRequestMessage(HttpMethod.Get, "api/files"));
            using var stream = resp.Content.ReadAsStream();
            using var doc = JsonDocument.Parse(stream);
            if (doc.RootElement.TryGetProperty("files", out var arr) && arr.ValueKind == JsonValueKind.Array)
            {
                foreach (var f in arr.EnumerateArray())
                {
                    fresh.Add(new FileMeta(
                        f.GetProperty("name").GetString() ?? "?",
                        f.GetProperty("size").GetInt64(),
                        f.TryGetProperty("modified", out var m) ? m.GetInt64() : 0));
                }
            }
        }
        catch
        {
            lock (_cacheLock) return _listing;
        }

        lock (_cacheLock)
        {
            _listing = fresh;
            _listingAge = DateTime.UtcNow;
        }
        return fresh;
    }

    private void InvalidateCache()
    {
        lock (_cacheLock) _listingAge = DateTime.MinValue;
    }

    private bool DownloadIntoBuffer(Handle h)
    {
        try
        {
            using var resp = _http.Send(new HttpRequestMessage(HttpMethod.Get,
                "dav/" + Uri.EscapeDataString(h.Node.Name)));
            if (!resp.IsSuccessStatusCode) return false;
            using var stream = resp.Content.ReadAsStream();
            stream.CopyTo(h.WriteBuffer!);
            h.WriteBuffer!.Position = 0;
            return true;
        }
        catch { return false; }
    }

    private static void PopulateFileInfo(FileMeta meta, ref FspFileInfo info)
    {
        info.FileAttributes = FileAttributes_Normal;
        info.FileSize = (ulong)meta.Size;
        info.AllocationSize = (ulong)meta.Size;
        var ft = ToFileTime(meta.Modified);
        info.LastWriteTime = ft;
        info.CreationTime = ft;
        info.LastAccessTime = ft;
        info.ChangeTime = ft;
    }

    private static ulong ToFileTime(long unixMillis) =>
        unixMillis > 0
            ? (ulong)DateTimeOffset.FromUnixTimeMilliseconds(unixMillis).UtcDateTime.ToFileTimeUtc()
            : (ulong)DateTime.UtcNow.ToFileTimeUtc();

    // -------- nested types ----------------------------------------------

    private sealed record FileMeta(string Name, long Size, long Modified);

    private sealed class Node
    {
        public string Name = "";
        public bool IsDirectory;
        public long Size;
        public long Modified;
    }

    private sealed class Handle
    {
        public Node Node = null!;
        public MemoryStream? WriteBuffer;
        public bool Dirty;
        public bool CreatedNew;
    }
}
