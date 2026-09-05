package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AiWebToolsTest {

    /** Minimal loopback HTTP server (the JDK HttpServer module is not on
     * the unit-test classpath, so raw sockets stand in). */
    private ServerSocket serverSocket;
    private Thread serverThread;
    private String base;
    private final Map<String, byte[]> bodies = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();
    private final Map<String, String> redirects = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        AiWebTools.setAllowPrivateForTests(true);
        serverSocket = new ServerSocket(0);
        base = "http://127.0.0.1:" + serverSocket.getLocalPort();
        bodies.put("/page", ("<html><head><title>T</title><style>.x{}</style></head>"
            + "<body><h1>Hello</h1><script>var x = 1;</script>"
            + "<p>World &amp; friends</p></body></html>").getBytes(StandardCharsets.UTF_8));
        types.put("/page", "text/html");
        bodies.put("/binary", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        types.put("/binary", "image/png");
        redirects.put("/redir", "/page");
        bodies.put("/search", new JSONObject()
            .put("results", new JSONArray()
                .put(new JSONObject().put("title", "First").put("url", base + "/page")
                    .put("content", "Snippet one."))
                .put(new JSONObject().put("title", "Blocked").put("url", "http://blocked.example/x")
                    .put("content", "Snippet two.")))
            .toString().getBytes(StandardCharsets.UTF_8));
        types.put("/search", "application/json");
        serverThread = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    try {
                        serve(socket);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            socket.close();
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }, "web-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void serve(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        int match = 0;
        byte[] tail = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        while ((b = in.read()) >= 0) {
            head.write(b);
            if (b == (tail[match] & 0xFF)) {
                if (++match == tail.length) break;
            } else {
                match = 0;
            }
            if (head.size() > 65536) break;
        }
        String[] requestLine = head.toString(StandardCharsets.UTF_8.name()).split(" ", 3);
        String path = requestLine.length > 1 ? requestLine[1] : "/";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        OutputStream out = socket.getOutputStream();
        if (redirects.containsKey(path)) {
            String response = "HTTP/1.1 302 Found\r\nLocation: " + redirects.get(path)
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = bodies.get(path);
        if (body == null) {
            String response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String header = "HTTP/1.1 200 OK\r\nContent-Type: " + types.get(path)
            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    @After
    public void tearDown() {
        AiWebTools.setAllowPrivateForTests(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    @Test
    public void policyBlocksPrivateAndSchemes() {
        AiWebTools.setAllowPrivateForTests(false);
        assertTrue(AiWebTools.policyRefusal("http://127.0.0.1/", "").contains("Private"));
        AiWebTools.setAllowPrivateForTests(true);
        assertEquals(null, AiWebTools.policyRefusal("http://127.0.0.1/", ""));
        assertTrue(AiWebTools.policyRefusal("ftp://example.com/x", "").contains("http(s)"));
        assertTrue(AiWebTools.policyRefusal("not a url", "").contains("http(s)"));
        assertTrue(AiWebTools.policyRefusal("https://sub.blocked.example/y", "blocked.example")
            .contains("blocklist"));
        assertTrue(AiWebTools.policyRefusal("https://blocked.example/y", "blocked.example")
            .contains("blocklist"));
        assertEquals(null, AiWebTools.policyRefusal("https://example.com/y", "other.example"));
    }

    @Test
    public void fetchExtractsTextFollowsRedirectsRefusesBinary() {
        JSONObject page = AiWebTools.fetch(base + "/page", "");
        assertTrue(page.toString(), page.optBoolean("success"));
        String content = page.optString("content");
        assertTrue(content, content.contains("Hello") && content.contains("World & friends"));
        assertFalse(content.contains("var x"));

        JSONObject redir = AiWebTools.fetch(base + "/redir", "");
        assertTrue(redir.optBoolean("success"));

        JSONObject binary = AiWebTools.fetch(base + "/binary", "");
        assertFalse(binary.optBoolean("success"));
        assertTrue(binary.optString("error").contains("Content-Type"));
    }

    @Test
    public void searxngSearchParsesAndFiltersBlocked() {
        JSONObject result = AiWebTools.search("hello world", 5, "searxng", base, "blocked.example");
        assertTrue(result.toString(), result.optBoolean("success"));
        assertEquals("searxng", result.optString("backend"));
        JSONArray results = result.optJSONArray("results");
        assertEquals(1, results.length());
        assertEquals("First", results.optJSONObject(0).optString("title"));
        assertTrue(results.optJSONObject(0).optString("snippet").contains("Snippet one"));
    }

    @Test
    public void searchValidatesAndReportsBackendErrors() {
        JSONObject empty = AiWebTools.search("   ", 5, "auto", "", "");
        assertFalse(empty.optBoolean("success"));

        JSONObject missing = AiWebTools.search("hello", 5, "searxng", "", "");
        assertFalse(missing.optBoolean("success"));
        assertTrue(missing.optString("error").contains("SearXNG"));

        JSONObject down = AiWebTools.search("hello", 5, "searxng", "http://127.0.0.1:1", "");
        assertFalse(down.optBoolean("success"));
    }
}
