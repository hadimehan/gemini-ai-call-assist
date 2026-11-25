const TARGET_HOST = "generativelanguage.googleapis.com";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
          "Access-Control-Allow-Headers": "*",
        },
      });
    }

    const url = new URL(request.url);

    if (env.PROXY_SECRET) {
      const token =
        request.headers.get("x-proxy-secret") ||
        url.searchParams.get("proxy_secret");
      if (token !== env.PROXY_SECRET) {
        return new Response("Unauthorized", { status: 401 });
      }
      url.searchParams.delete("proxy_secret");
    }

    url.hostname = TARGET_HOST;
    url.protocol = "https:";
    url.port = "";

    if (
      env.GEMINI_API_KEY &&
      !url.searchParams.has("key") &&
      !request.headers.has("x-goog-api-key")
    ) {
      url.searchParams.set("key", env.GEMINI_API_KEY);
    }

    const headers = new Headers(request.headers);
    headers.set("Host", TARGET_HOST);
    const stripHeaders = [
      "cf-connecting-ip",
      "cf-ipcountry",
      "cf-ray",
      "cf-visitor",
      "x-forwarded-for",
      "x-real-ip",
      "origin",
      "referer",
    ];
    for (const h of stripHeaders) {
      headers.delete(h);
    }

    const upstreamRequest = new Request(url.toString(), {
      method: request.method,
      headers,
      body:
        request.method === "GET" || request.method === "HEAD"
          ? undefined
          : request.body,
      redirect: "follow",
    });

    const response = await fetch(upstreamRequest);

    if (response.status === 101) {
      return response;
    }

    const newResponse = new Response(response.body, response);
    newResponse.headers.set("Access-Control-Allow-Origin", "*");
    return newResponse;
  },
};
