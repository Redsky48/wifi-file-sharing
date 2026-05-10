using System;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;

namespace WiFiShareTray;

public sealed record UploadOutcome(bool Success, string FileName, string? Error)
{
    public static UploadOutcome Ok(string name) => new(true, name, null);
    public static UploadOutcome Fail(string name, string error) => new(false, name, error);
}

/// <summary>
/// Streams a local file to the phone's /api/upload endpoint as multipart
/// form-data. Used by the Send-To handler / tray drag flow.
/// </summary>
internal static class Uploader
{
    public static async Task<UploadOutcome> UploadAsync(
        string baseUrl,
        string? password,
        string filePath)
    {
        var name = Path.GetFileName(filePath);
        if (string.IsNullOrEmpty(baseUrl)) return UploadOutcome.Fail(name, "Not connected");
        if (!File.Exists(filePath)) return UploadOutcome.Fail(name, "File not found");

        try
        {
            using var client = AuthHttp.Build(new Uri(baseUrl), password, TimeSpan.FromMinutes(15));
            await using var stream = new FileStream(
                filePath, FileMode.Open, FileAccess.Read, FileShare.Read,
                bufferSize: 64 * 1024, useAsync: true);

            using var content = new MultipartFormDataContent();
            var fileContent = new StreamContent(stream);
            fileContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");

            // Set Content-Disposition explicitly so non-ASCII filenames
            // (Latvian āēīšž, emoji, etc.) survive the round-trip. RFC 5987
            // filename* = utf-8 percent-encoded; legacy filename = ASCII
            // fallback. NanoHTTPD's multipart parser picks the first valid
            // filename, so quoting the legacy one keeps it intact.
            fileContent.Headers.ContentDisposition = new ContentDispositionHeaderValue("form-data")
            {
                Name = "\"file\"",
                FileName = "\"" + AsciiSafe(name) + "\"",
                FileNameStar = name,
            };
            content.Add(fileContent);

            using var resp = await client.PostAsync("/api/upload", content);
            if (resp.IsSuccessStatusCode) return UploadOutcome.Ok(name);
            return UploadOutcome.Fail(name, $"HTTP {(int)resp.StatusCode}");
        }
        catch (Exception ex)
        {
            return UploadOutcome.Fail(name, ex.Message);
        }
    }

    /// <summary>Replace non-ASCII chars with '_' so the legacy filename header is HTTP-safe.</summary>
    private static string AsciiSafe(string s)
    {
        var sb = new System.Text.StringBuilder(s.Length);
        foreach (var c in s)
        {
            sb.Append(c < 128 && c != '"' ? c : '_');
        }
        return sb.ToString();
    }
}
