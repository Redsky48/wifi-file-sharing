using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;

namespace WiFiShareTray;

/// <summary>
/// SOAP client for UPnP AVTransport:1 — the service every DLNA Media
/// Renderer (smart TV, network speaker, etc.) implements for media
/// playback control. Three methods cover the cast lifecycle:
///   - [SetUriAsync] tells the renderer where to fetch the media from
///   - [PlayAsync] starts playback
///   - [StopAsync] tears it down
///
/// We send raw HTTP POSTs with a hand-crafted XML envelope; the surface
/// is small enough that a SOAP library would be overkill, and avoids a
/// 5 MB NuGet dependency.
/// </summary>
public static class DlnaClient
{
    private const string AvTransport = "urn:schemas-upnp-org:service:AVTransport:1";

    public static Task<bool> SetUriAsync(
        DlnaRenderer renderer, string mediaUrl, string? mimeHint = null,
        string? title = null)
    {
        // DIDL-Lite metadata is optional but many TVs use it to pick the
        // right player UI (audio vs. video vs. image). Inline a minimal
        // record — empty metadata also works on most renderers.
        var didl = BuildDidlLite(mediaUrl, mimeHint, title);
        var body = SoapEnvelope("SetAVTransportURI",
            $"<InstanceID>0</InstanceID>" +
            $"<CurrentURI>{XmlEscape(mediaUrl)}</CurrentURI>" +
            $"<CurrentURIMetaData>{XmlEscape(didl)}</CurrentURIMetaData>");
        return PostAsync(renderer, "SetAVTransportURI", body);
    }

    public static Task<bool> PlayAsync(DlnaRenderer renderer)
    {
        var body = SoapEnvelope("Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>");
        return PostAsync(renderer, "Play", body);
    }

    public static Task<bool> StopAsync(DlnaRenderer renderer)
    {
        var body = SoapEnvelope("Stop", "<InstanceID>0</InstanceID>");
        return PostAsync(renderer, "Stop", body);
    }

    public static Task<bool> PauseAsync(DlnaRenderer renderer)
    {
        var body = SoapEnvelope("Pause", "<InstanceID>0</InstanceID>");
        return PostAsync(renderer, "Pause", body);
    }

    private static async Task<bool> PostAsync(DlnaRenderer renderer, string action, string body)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            using var req = new HttpRequestMessage(HttpMethod.Post, renderer.AvTransportControlUrl);
            // text/xml; charset="utf-8" — most permissive form. SOAPACTION
            // header is REQUIRED; the value uses literal double-quotes.
            req.Content = new StringContent(body, Encoding.UTF8);
            req.Content.Headers.ContentType = new MediaTypeHeaderValue("text/xml") { CharSet = "utf-8" };
            req.Headers.TryAddWithoutValidation("SOAPACTION", $"\"{AvTransport}#{action}\"");
            using var resp = await http.SendAsync(req);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private static string SoapEnvelope(string action, string innerXml)
    {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
               "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                  "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                  "<s:Body>" +
                    $"<u:{action} xmlns:u=\"{AvTransport}\">{innerXml}</u:{action}>" +
                  "</s:Body>" +
                "</s:Envelope>";
    }

    private static string BuildDidlLite(string mediaUrl, string? mime, string? title)
    {
        var mediaTitle = title ?? System.IO.Path.GetFileName(new Uri(mediaUrl).LocalPath);
        if (string.IsNullOrEmpty(mediaTitle)) mediaTitle = "WiFi Share cast";
        var upnpClass = (mime ?? "").ToLowerInvariant() switch
        {
            string s when s.StartsWith("image/") => "object.item.imageItem.photo",
            string s when s.StartsWith("audio/") => "object.item.audioItem.musicTrack",
            _ => "object.item.videoItem",
        };
        var protocolInfo = $"http-get:*:{mime ?? "*/*"}:*";
        return "<DIDL-Lite " +
                 "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                 "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                 "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                 "<item id=\"1\" parentID=\"0\" restricted=\"1\">" +
                   $"<dc:title>{XmlEscape(mediaTitle)}</dc:title>" +
                   $"<upnp:class>{upnpClass}</upnp:class>" +
                   $"<res protocolInfo=\"{XmlEscape(protocolInfo)}\">{XmlEscape(mediaUrl)}</res>" +
                 "</item>" +
               "</DIDL-Lite>";
    }

    private static string XmlEscape(string s) =>
        s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;")
         .Replace("\"", "&quot;").Replace("'", "&apos;");
}
