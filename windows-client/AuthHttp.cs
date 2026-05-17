using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace WiFiShareTray;

internal static class AuthHttp
{
    /// <summary>
    /// Build an HttpClient pre-configured with whatever credential we
    /// hold for this phone:
    ///   - If [bearerToken] is set, send <c>Authorization: Bearer …</c>
    ///     (preferred — per-device, revocable from the phone).
    ///   - Else if [password] is set, send <c>Authorization: Basic user:PIN</c>
    ///     (used during the very first connect, before we've paired and
    ///     received a token).
    ///   - Else send nothing — phone must be PIN-less.
    ///
    /// Username "user" is arbitrary; our server only checks the password
    /// component of Basic.
    /// </summary>
    public static HttpClient Build(Uri baseAddress, string? password, TimeSpan timeout, string? bearerToken = null)
    {
        var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
        {
            BaseAddress = baseAddress,
            Timeout = timeout,
        };
        ApplyAuth(client.DefaultRequestHeaders, password, bearerToken);
        return client;
    }

    public static void ApplyAuth(HttpRequestHeaders headers, string? password, string? bearerToken = null)
    {
        if (!string.IsNullOrEmpty(bearerToken))
        {
            headers.Authorization = new AuthenticationHeaderValue("Bearer", bearerToken);
            return;
        }
        if (string.IsNullOrEmpty(password)) return;
        var token = Convert.ToBase64String(
            Encoding.UTF8.GetBytes($"user:{password}"));
        headers.Authorization = new AuthenticationHeaderValue("Basic", token);
    }
}
