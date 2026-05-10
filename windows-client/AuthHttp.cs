using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace WiFiShareTray;

internal static class AuthHttp
{
    /// <summary>
    /// Build an HttpClient pre-configured with HTTP Basic Auth if a
    /// password is provided. Username "user" — accepted by our server,
    /// which only checks the password component.
    /// </summary>
    public static HttpClient Build(Uri baseAddress, string? password, TimeSpan timeout)
    {
        var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
        {
            BaseAddress = baseAddress,
            Timeout = timeout,
        };
        ApplyAuth(client.DefaultRequestHeaders, password);
        return client;
    }

    public static void ApplyAuth(HttpRequestHeaders headers, string? password)
    {
        if (string.IsNullOrEmpty(password)) return;
        var token = Convert.ToBase64String(
            Encoding.UTF8.GetBytes($"user:{password}"));
        headers.Authorization = new AuthenticationHeaderValue("Basic", token);
    }
}
