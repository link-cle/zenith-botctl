package com.dsh.botctl;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin HTTP client for the ZenithProxy WebUI plugin API.
 * All methods are blocking - call them off the UI thread.
 */
public class Api {

    public interface LogSink {
        void onLog(JSONObject entry);

        void onError(String msg);
    }

    private String base;      // e.g. http://1.2.3.4:8080
    private String cookie;    // webui_token=<uuid>

    public Api(String host, int port) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("http://")) h = h.substring(7);
        if (h.startsWith("https://")) h = h.substring(8);
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        // allow "host:port" pasted directly
        int c = h.indexOf(':');
        if (c > 0) {
            try {
                port = Integer.parseInt(h.substring(c + 1));
                h = h.substring(0, c);
            } catch (Exception ignored) {
            }
        }
        this.base = "http://" + h + ":" + port;
    }

    public String base() {
        return base;
    }

    public void setCookie(String c) {
        this.cookie = c;
    }

    public String cookie() {
        return cookie;
    }

    public boolean hasCookie() {
        return cookie != null && cookie.length() > 0;
    }

    // ---------------------------------------------------------------- auth

    /** POST /api/auth/login. On success stores the webui_token cookie. */
    public boolean login(String user, String pass) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", user);
        body.put("password", pass);
        HttpURLConnection c = open("/api/auth/login", "POST");
        c.setRequestProperty("Content-Type", "application/json");
        write(c, body.toString());
        int code = c.getResponseCode();
        String resp = read(c, code);
        if (code != 200) {
            throw new Exception("登录失败 HTTP " + code + " " + resp);
        }
        String setCookie = c.getHeaderField("Set-Cookie");
        if (setCookie != null) {
            String[] parts = setCookie.split(";");
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("webui_token=")) {
                    cookie = t;
                }
            }
        }
        if (cookie == null) {
            // fall back: some builds return the token in the body
            try {
                JSONObject o = new JSONObject(resp);
                if (o.has("token")) cookie = "webui_token=" + o.getString("token");
            } catch (Exception ignored) {
            }
        }
        return resp.contains("\"success\":true") && cookie != null;
    }

    /** GET /api/auth/status */
    public JSONObject authStatus() throws Exception {
        return get("/api/auth/status");
    }

    // ----------------------------------------------------------- dashboard

    public JSONObject dashboard() throws Exception {
        return get("/api/dashboard");
    }

    // ------------------------------------------------------------ commands

    /** POST /api/commands -> {"success":bool,"output":[lines]} */
    public JSONObject command(String cmd) throws Exception {
        JSONObject body = new JSONObject();
        body.put("command", cmd);
        HttpURLConnection c = open("/api/commands", "POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        write(c, body.toString());
        int code = c.getResponseCode();
        String resp = read(c, code);
        if (code != 200) throw new Exception("命令失败 HTTP " + code + " " + resp);
        return new JSONObject(resp);
    }

    /** Runs a command and returns its output lines joined by newline. */
    public String commandText(String cmd) throws Exception {
        JSONObject o = command(cmd);
        StringBuilder sb = new StringBuilder();
        Object out = o.opt("output");
        if (out instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) out;
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(arr.optString(i));
            }
        } else if (out != null) {
            sb.append(String.valueOf(out));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------- whitelist

    public JSONObject whitelist() throws Exception {
        return get("/api/whitelist");
    }

    public JSONObject whitelistAction(String action, String username) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", action);
        body.put("username", username);
        HttpURLConnection c = open("/api/whitelist", "POST");
        c.setRequestProperty("Content-Type", "application/json");
        write(c, body.toString());
        int code = c.getResponseCode();
        String resp = read(c, code);
        if (code != 200) throw new Exception("白名单操作失败 HTTP " + code + " " + resp);
        return new JSONObject(resp);
    }

    // ---------------------------------------------------------------- logs

    /**
     * Long-lived SSE reader. Blocks until the socket dies; call from a worker thread.
     * Uses a short read timeout so `running` flags can be checked periodically.
     */
    public void streamLogs(boolean[] running, LogSink sink) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = open("/api/logs/stream", "GET");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setConnectTimeout(8000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            if (code != 200) {
                sink.onError("日志流 HTTP " + code);
                return;
            }
            InputStream in = c.getInputStream();
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder payload = new StringBuilder();
            while (running[0]) {
                String line;
                try {
                    line = r.readLine();
                } catch (java.net.SocketTimeoutException te) {
                    continue;   // idle keep-alive window, just loop
                }
                if (line == null) break;
                if (line.length() == 0) {
                    if (payload.length() > 0) {
                        String raw = payload.toString();
                        payload.setLength(0);
                        if (raw.startsWith("data:")) raw = raw.substring(5).trim();
                        try {
                            sink.onLog(new JSONObject(raw));
                        } catch (Exception ignored) {
                            // non-JSON frame, ignore
                        }
                    }
                    continue;
                }
                if (line.startsWith(":")) continue;      // comment / heartbeat
                if (payload.length() > 0) payload.append('\n');
                payload.append(line);
            }
        } catch (Exception e) {
            if (running[0]) sink.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- http

    private JSONObject get(String path) throws Exception {
        HttpURLConnection c = open(path, "GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        int code = c.getResponseCode();
        String resp = read(c, code);
        if (code != 200) throw new Exception("HTTP " + code + " " + resp);
        return new JSONObject(resp);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL u = new URL(base + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "BotCtl-Android/4.0");
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if ("POST".equals(method)) c.setDoOutput(true);
        return c;
    }

    private void write(HttpURLConnection c, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
    }

    private String read(HttpURLConnection c, int code) throws Exception {
        InputStream in;
        try {
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        } catch (Exception e) {
            in = c.getErrorStream();
        }
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** Splits "host:port" into {host, port} with a default port. */
    public static String[] splitHostPort(String input, int defPort) {
        String s = input == null ? "" : input.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("https://")) s = s.substring(8);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String host = s;
        int port = defPort;
        int c = s.lastIndexOf(':');
        if (c > 0) {
            try {
                port = Integer.parseInt(s.substring(c + 1));
                host = s.substring(0, c);
            } catch (Exception ignored) {
                host = s;
            }
        }
        return new String[]{host, String.valueOf(port)};
    }

    /** Parses "Coordinates: ||[8440244, 70, 11622371]||" into {x,y,z}. */
    public static Integer[] parseCoords(String statusText) {
        if (statusText == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Coordinates:\\s*\\|*\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
                .matcher(statusText);
        if (m.find()) {
            try {
                return new Integer[]{Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Extracts "Dimension: overworld" style values. */
    public static String field(String statusText, String key) {
        if (statusText == null) return "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + ":\\s*(.+)$")
                    .matcher(statusText);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {
        }
        return "";
    }

    public static List<String> lines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        for (String s : text.split("\n")) {
            if (s.trim().length() > 0) out.add(s.trim());
        }
        return out;
    }
}
