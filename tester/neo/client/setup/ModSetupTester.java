package neo.client.setup;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ModSetup のテスト。ローカルの仮Modrinthサーバー(v2のレスポンス形式を再現)で確認する。
 * 実行: javac -cp neoclient.jar -d testout tester/ModSetupTester.java
 *       java -Dtest.root=<空の作業dir> -cp neoclient.jar:testout neo.client.setup.ModSetupTester   (最後に ALL PASS)
 */
public class ModSetupTester {

    static final String MC = "26.3";

    static class Ver {
        String type, number, date, filename, urlOverride, hashOverride, id;
        byte[] content;
        /** この版が「必須」と指定する他MODの版ID */
        List<String> requires = new ArrayList<>();
        Ver(String type, String number, String date, String filename, byte[] content) {
            this.type = type; this.number = number; this.date = date;
            this.filename = filename; this.content = content;
            this.id = filename;
        }
        Ver id(String v) { this.id = v; return this; }
        Ver requires(String... ids) { this.requires.addAll(List.of(ids)); return this; }
    }

    static final Map<String, List<Ver>> MODS = new LinkedHashMap<>();
    static final AtomicInteger API_REQ = new AtomicInteger();
    static final AtomicInteger FILE_REQ = new AtomicInteger();
    static final AtomicInteger UA_BAD = new AtomicInteger();
    static final AtomicInteger GH_REQ = new AtomicInteger();
    static final AtomicInteger GH_FILE_REQ = new AtomicInteger();
    static byte[] neoJar;
    static volatile String neoDigestOverride;
    static volatile String mockMode = "";
    static volatile byte[] clientJson = "{\"id\":\".Neo-Client\",\"v\":2}".getBytes(StandardCharsets.UTF_8);
    static HttpServer server;
    static int port;
    static int pass = 0, fail = 0;

    static byte[] jar(String modId, String salt) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"" + salt + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bo.toByteArray();
    }

    static String sha256(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String sha512(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-512").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 実際のModrinthの26.3向け構成を模す: Sodiumは新しいbeta/alphaが先頭、releaseはその下 */
    static void seed() throws Exception {
        MODS.clear();
        MODS.put("fabric-api", new ArrayList<>(List.of(
                new Ver("release", "0.165.0+26.3", "2026-10-23T08:35:46Z", "fabric-api-0.165.0+26.3.jar", jar("fabric-api", "165")),
                new Ver("release", "0.164.0+26.3", "2026-10-19T10:10:38Z", "fabric-api-0.164.0+26.3.jar", jar("fabric-api", "164")))));
        MODS.put("sodium", new ArrayList<>(List.of(
                new Ver("beta", "mc26.3-0.9.3-beta.1-fabric", "2026-10-15T17:51:44Z", "sodium-fabric-0.9.3-beta.1+mc26.3.jar", jar("sodium", "beta")).id("SODB1"),
                new Ver("alpha", "mc26.3-0.9.3-alpha.1-fabric", "2026-09-20T21:27:07Z", "sodium-fabric-0.9.3-alpha.1+mc26.3.jar", jar("sodium", "alpha")).id("SODA1"),
                new Ver("release", "mc26.3-0.9.2-fabric", "2026-09-15T15:57:23Z", "sodium-fabric-0.9.2+mc26.3.jar", jar("sodium", "092")).id("SOD092"))));
        MODS.put("iris", new ArrayList<>(List.of(
                new Ver("release", "1.11.7+26.3-fabric", "2026-09-30T02:57:43Z", "iris-fabric-1.11.7+mc26.3.jar", jar("iris", "1117")).requires("SOD092"))));
        neoJar = jar("neo", "263");
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v2/project/", ex -> {
            API_REQ.incrementAndGet();
            String ua = ex.getRequestHeaders().getFirst("User-Agent");
            if (ua == null || ua.startsWith("Java/")) UA_BAD.incrementAndGet();
            String slug = ex.getRequestURI().getPath().split("/")[3];
            String q = java.net.URLDecoder.decode(String.valueOf(ex.getRequestURI().getRawQuery()), StandardCharsets.UTF_8);
            byte[] out;
            if ("MALFORMED".equals(mockMode)) {
                out = "{not json".getBytes(StandardCharsets.UTF_8);
            } else if (!MODS.containsKey(slug) || !q.contains("[\"" + MC + "\"]") || !q.contains("[\"fabric\"]")) {
                out = "[]".getBytes(StandardCharsets.UTF_8);
            } else {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Ver v : MODS.get(slug)) {
                    if (!first) sb.append(",");
                    first = false;
                    try {
                        String url = v.urlOverride != null ? v.urlOverride : "http://127.0.0.1:" + port + "/files/" + v.filename;
                        String hash = v.hashOverride != null ? v.hashOverride : sha512(v.content);
                        sb.append("{\"version_number\":\"").append(esc(v.number)).append("\",")
                          .append("\"version_type\":\"").append(v.type).append("\",")
                          .append("\"date_published\":\"").append(v.date).append("\",")
                          .append("\"id\":\"").append(esc(v.id)).append("\",")
                          .append("\"game_versions\":[\"").append(MC).append("\"],\"dependencies\":[")
                          .append(String.join(",", v.requires.stream().map(r ->
                                  "{\"project_id\":\"AANobbMI\",\"version_id\":\"" + r + "\",\"dependency_type\":\"required\"}").toList()))
                          .append("],")
                          .append("\"files\":[{\"hashes\":{\"sha1\":\"x\",\"sha512\":\"").append(hash).append("\"},")
                          .append("\"url\":\"").append(esc(url)).append("\",")
                          .append("\"filename\":\"").append(esc(v.filename)).append("\",")
                          .append("\"primary\":true,\"size\":").append(v.content.length).append("}]}");
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                out = sb.append("]").toString().getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/files/", ex -> {
            FILE_REQ.incrementAndGet();
            String name = ex.getRequestURI().getPath().substring("/files/".length());
            byte[] body = null;
            for (List<Ver> l : MODS.values()) for (Ver v : l) if (v.filename.equals(name)) body = v.content;
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        // GitHub Releases(NeoMods)。新しい順: プレリリース → 別のMC向け → 26.3向け
        server.createContext("/repos/nemonowa/NeoMod/releases", ex -> {
            GH_REQ.incrementAndGet();
            String digest;
            try { digest = neoDigestOverride != null ? neoDigestOverride : "sha256:" + sha256(neoJar); }
            catch (Exception e) { throw new RuntimeException(e); }
            String base = "http://127.0.0.1:" + port + "/gh/";
            String asset = "{\"name\":\"NeoMod.jar\",\"size\":" + neoJar.length + ",\"digest\":\"" + digest
                    + "\",\"browser_download_url\":\"" + base + "NeoMod.jar\"}";
            String other = "{\"name\":\"NeoMod.jar\",\"size\":1,\"digest\":\"sha256:" + "0".repeat(64)
                    + "\",\"browser_download_url\":\"" + base + "other.jar\"}";
            byte[] out = ("[{\"tag_name\":\"v26.4-pre\",\"draft\":false,\"prerelease\":true,\"assets\":[" + other + "]},"
                    + "{\"tag_name\":\"v26.4\",\"draft\":false,\"prerelease\":false,\"assets\":[" + other + "]},"
                    + "{\"tag_name\":\"v" + MC + "\",\"draft\":false,\"prerelease\":false,\"assets\":[" + asset + "]}]")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        // .Neo-Client 自身の最新リリース(Neo-Client.json)
        server.createContext("/repos/nemonowa/.Neo-Client/releases/latest", ex -> {
            String digest;
            try { digest = "sha256:" + sha256(clientJson); } catch (Exception e) { throw new RuntimeException(e); }
            byte[] out = ("{\"tag_name\":\"v1.0\",\"assets\":[{\"name\":\"Neo-Client.json\",\"digest\":\"" + digest
                    + "\",\"browser_download_url\":\"http://127.0.0.1:" + port + "/cj/Neo-Client.json\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/cj/", ex -> {
            byte[] body = clientJson;
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/gh/", ex -> {
            GH_FILE_REQ.incrementAndGet();
            if (!ex.getRequestURI().getPath().equals("/gh/NeoMod.jar")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, neoJar.length);
            ex.getResponseBody().write(neoJar);
            ex.close();
        });
        server.start();
        System.setProperty("neo.modrinthApi", "http://127.0.0.1:" + port);
        System.setProperty("neo.githubApi", "http://127.0.0.1:" + port);
    }

    record Result(boolean ok, String log) {}

    static Result run(File gameDir) {
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(bo, true, StandardCharsets.UTF_8);
        System.setOut(cap); System.setErr(cap);
        boolean ok;
        try { ok = ModSetup.ensure(gameDir, MC); }
        finally { System.setOut(o); System.setErr(e); }
        return new Result(ok, bo.toString(StandardCharsets.UTF_8));
    }

    static Result runUpdate(File versionDir) {
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(bo, true, StandardCharsets.UTF_8);
        System.setOut(cap); System.setErr(cap);
        try { ModSetup.updateVersionJson(versionDir); }
        finally { System.setOut(o); System.setErr(e); }
        return new Result(true, bo.toString(StandardCharsets.UTF_8));
    }

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static File freshDir() throws Exception {
        return Files.createTempDirectory(new File(System.getProperty("test.root")).toPath(), "game").toFile();
    }
    static boolean has(File mods, String n) { return new File(mods, n).isFile(); }
    static boolean noPart(File mods) {
        String[] l = mods.list((d, n) -> n.endsWith(".part"));
        return l == null || l.length == 0;
    }
    static byte[] read(File f) throws Exception { return Files.readAllBytes(f.toPath()); }

    public static void main(String[] args) throws Exception {
        startServer();
        seed();
        Result r;

        System.out.println("T1: 初回 — 3つ取得。Sodiumは新しいbeta/alphaではなくreleaseを選ぶ");
        File g1 = freshDir(); File m1 = new File(g1, "mods");
        r = run(g1);
        check("戻り値 true", r.ok());
        check("fabric-api は最新release 0.165.0", has(m1, "fabric-api-0.165.0+26.3.jar") && !has(m1, "fabric-api-0.164.0+26.3.jar"));
        check("sodium は release 0.9.2(beta/alphaは入れない)", has(m1, "sodium-fabric-0.9.2+mc26.3.jar")
                && !has(m1, "sodium-fabric-0.9.3-beta.1+mc26.3.jar") && !has(m1, "sodium-fabric-0.9.3-alpha.1+mc26.3.jar"));
        check("iris を配置(+mc26.3形式)", has(m1, "iris-fabric-1.11.7+mc26.3.jar"));
        check("NeoMods は GitHub の v26.3 から配置(プレリリース・別のMC向けは使わない)", has(m1, "NeoMod.jar")
                && sha256(read(new File(m1, "NeoMod.jar"))).equals(sha256(neoJar)));
        check("GitHub は API 1回 + ファイル 1回", GH_REQ.get() == 1 && GH_FILE_REQ.get() == 1);
        check("API 3回 + ファイル 3回", API_REQ.get() == 3 && FILE_REQ.get() == 3);
        check(".part の取り残しなし", noPart(m1));
        check("状態ファイルが versions/.Neo-Client/ に作られる", new File(g1, "versions/.Neo-Client/neoclient_mods.properties").isFile());
        check("User-Agent が Java 既定値ではない", UA_BAD.get() == 0);
        String st1 = Files.readString(new File(g1, "versions/.Neo-Client/neoclient_mods.properties").toPath());
        check("irisが必須とするsodiumの版IDを状態に記録", st1.contains("iris.requires=SOD092"));

        System.out.println("T2: 再実行 — ネットワークに一切接続しない(+mc26.3 形式でも導入済みと判定)");
        int a = API_REQ.get(), f = FILE_REQ.get();
        r = run(g1);
        check("Modrinth へのリクエスト 0・NeoMods は最新の確認だけ(API +1、ファイル 0)", API_REQ.get() == a && FILE_REQ.get() == f
                && GH_REQ.get() == 2 && GH_FILE_REQ.get() == 1);
        check("戻り値 true・警告なし", r.ok() && !r.log().contains("警告"));
        check("3つとも OK 表示", r.log().contains("fabric-api: OK") && r.log().contains("sodium: OK") && r.log().contains("iris: OK"));

        System.out.println("T3: jarが壊れていたら再取得して直す");
        File sodium = new File(m1, "sodium-fabric-0.9.2+mc26.3.jar");
        Files.write(sodium.toPath(), "garbage".getBytes());
        f = FILE_REQ.get();
        run(g1);
        check("sodium だけ再取得(ファイル +1)", FILE_REQ.get() == f + 1);
        check("内容が正しいjarに戻った", sha512(read(sodium)).equals(sha512(MODS.get("sodium").get(2).content)));

        System.out.println("T4: 更新(neo.updateMods) — 旧版を削除し、ユーザーの別MODには触らない");
        Files.write(new File(m1, "my-other-mod.jar").toPath(), jar("othermod", "x"));
        MODS.get("fabric-api").add(0, new Ver("release", "0.166.0+26.3", "2026-10-28T08:00:00Z", "fabric-api-0.166.0+26.3.jar", jar("fabric-api", "166")));
        System.setProperty("neo.updateMods", "true");
        a = API_REQ.get(); f = FILE_REQ.get();
        run(g1);
        System.clearProperty("neo.updateMods");
        check("新しい fabric-api を配置", has(m1, "fabric-api-0.166.0+26.3.jar"));
        check("旧 fabric-api を削除", !has(m1, "fabric-api-0.165.0+26.3.jar"));
        check("ユーザーの別MODは無傷", has(m1, "my-other-mod.jar"));
        check("変更のないMODは再ダウンロードしない(ファイル +1 のみ)", FILE_REQ.get() == f + 1);
        check("更新確認でAPIを3回呼ぶ", API_REQ.get() == a + 3);
        MODS.get("fabric-api").remove(0);

        System.out.println("T5: ハッシュ不一致(改ざん)は配置しない");
        MODS.get("iris").get(0).hashOverride = sha512("different".getBytes());
        File g5 = freshDir(); File m5 = new File(g5, "mods");
        r = run(g5);
        check("戻り値 false", !r.ok());
        check("iris は配置されない・.partも残らない", !has(m5, "iris-fabric-1.11.7+mc26.3.jar") && noPart(m5));
        check("他の2つは配置される", has(m5, "fabric-api-0.165.0+26.3.jar") && has(m5, "sodium-fabric-0.9.2+mc26.3.jar"));
        check("警告が出て例外にならない", r.log().contains("警告") && r.log().contains("SHA-512"));
        MODS.get("iris").get(0).hashOverride = null;

        System.out.println("T6: 危険なファイル名(../evil.jar)を拒否");
        List<Ver> backup = MODS.get("fabric-api");
        MODS.put("fabric-api", new ArrayList<>(List.of(new Ver("release", "9", "2026-10-01T00:00:00Z", "../evil.jar", jar("fabric-api", "evil")))));
        File g6 = freshDir();
        r = run(g6);
        check("modsの外にファイルが作られない", !new File(g6, "evil.jar").exists());
        check("警告が出る", r.log().contains("安全でないファイル名"));
        MODS.put("fabric-api", backup);

        System.out.println("T7: 許可されていないダウンロード元を拒否");
        MODS.get("sodium").get(2).urlOverride = "http://evil.example.com/sodium.jar";
        File g7 = freshDir();
        r = run(g7);
        check("sodium は配置されない", !has(new File(g7, "mods"), "sodium-fabric-0.9.2+mc26.3.jar"));
        check("警告が出る", r.log().contains("想定外のダウンロード元"));
        MODS.get("sodium").get(2).urlOverride = null;

        System.out.println("T8: APIの応答が壊れていても落ちない");
        mockMode = "MALFORMED";
        r = run(freshDir());
        mockMode = "";
        check("例外にならず警告のみ・false", !r.ok() && r.log().contains("3 個"));

        System.out.println("T9: 重複MODの警告(自動削除はしない)");
        File g9 = freshDir(); File m9 = new File(g9, "mods"); m9.mkdirs();
        Files.write(new File(m9, "sodium-old-by-user.jar").toPath(), jar("sodium", "old"));
        r = run(g9);
        check("重複警告が出る", r.log().contains("'sodium'") && r.log().contains("重複"));
        check("ユーザーのjarは消さない", has(m9, "sodium-old-by-user.jar"));

        System.out.println("T10: releaseが無くbetaだけなら beta を使う / alphaだけなら入れない");
        List<Ver> sodiumBackup = MODS.get("sodium");
        MODS.put("sodium", new ArrayList<>(List.of(sodiumBackup.get(0), sodiumBackup.get(1)))); // beta + alpha
        File g10 = freshDir();
        r = run(g10);
        check("beta を採用", has(new File(g10, "mods"), "sodium-fabric-0.9.3-beta.1+mc26.3.jar"));
        check("alpha は入れない", !has(new File(g10, "mods"), "sodium-fabric-0.9.3-alpha.1+mc26.3.jar"));
        MODS.put("sodium", new ArrayList<>(List.of(sodiumBackup.get(1)))); // alphaのみ
        File g10b = freshDir();
        r = run(g10b);
        check("alphaのみなら導入せず警告(false)", !r.ok() && !has(new File(g10b, "mods"), "sodium-fabric-0.9.3-alpha.1+mc26.3.jar")
                && r.log().contains("alpha"));
        MODS.put("sodium", sodiumBackup);

        System.out.println("T11: 対応版が無いMCバージョンでは警告のみ");
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(bo, true, StandardCharsets.UTF_8);
        System.setOut(cap); System.setErr(cap);
        boolean okOther;
        try { okOther = ModSetup.ensure(freshDir(), "99.9"); } finally { System.setOut(o); System.setErr(e); }
        check("false・「見つかりません」の警告", !okOther && bo.toString(StandardCharsets.UTF_8).contains("見つかりません"));

        System.out.println("T12: MCバージョンが変わったら再解決して旧ファイルを入れ替える");
        File g12 = freshDir(); File m12 = new File(g12, "mods");
        run(g12);
        File st = new File(g12, "versions/.Neo-Client/neoclient_mods.properties");
        String txt = Files.readString(st.toPath()).replace("fabric-api.mc=26.3", "fabric-api.mc=26.2");
        Files.writeString(st.toPath(), txt);
        a = API_REQ.get();
        run(g12);
        check("fabric-api だけAPIを再問い合わせ(+1)", API_REQ.get() == a + 1);
        check("状態のMCが26.3に戻る", Files.readString(st.toPath()).contains("fabric-api.mc=26.3"));

        System.out.println("T13: 状態ファイルが壊れていても落ちない(jarが一致すれば採用)");
        File g13 = freshDir(); File m13 = new File(g13, "mods");
        run(g13);
        Files.write(new File(g13, "versions/.Neo-Client/neoclient_mods.properties").toPath(), new byte[]{(byte) 0x5c, 'u', 'z'});
        f = FILE_REQ.get();
        r = run(g13);
        check("再取得せずに成功(既存jarのハッシュが一致)", r.ok() && FILE_REQ.get() == f);

        System.out.println("T14: neo.skipModSetup");
        System.setProperty("neo.skipModSetup", "true");
        File g14 = freshDir();
        r = run(g14);
        System.clearProperty("neo.skipModSetup");
        check("スキップのみ・true・mods作らない", r.ok() && r.log().contains("スキップ") && !new File(g14, "mods").exists());

        System.out.println("T16: Irisが指定したSodiumの版を、newer releaseより優先する");
        // Sodiumに「より新しいrelease 0.9.3」が出たが、Irisは0.9.2(SOD092)を必須指定している状況
        List<Ver> sodBackup = MODS.get("sodium");
        List<Ver> sodNew = new ArrayList<>(sodBackup);
        sodNew.add(0, new Ver("release", "mc26.3-0.9.3-fabric", "2026-10-29T10:00:00Z", "sodium-fabric-0.9.3+mc26.3.jar", jar("sodium", "093")).id("SOD093"));
        MODS.put("sodium", sodNew);
        File g16 = freshDir(); File m16 = new File(g16, "mods");
        r = run(g16);
        check("newer release(0.9.3)ではなく、Irisが指定した0.9.2を入れる", has(m16, "sodium-fabric-0.9.2+mc26.3.jar") && !has(m16, "sodium-fabric-0.9.3+mc26.3.jar"));
        check("ログに「必須と指定した版」と出る", r.log().contains("他のMODが必須と指定した版"));
        // Irisが別の版(0.9.3)を指定するようになったら、更新時にそちらへ追従する
        MODS.get("iris").get(0).requires.clear();
        MODS.get("iris").get(0).requires.add("SOD093");
        MODS.get("iris").get(0).id = "IRIS-NEW";
        System.setProperty("neo.updateMods", "true");
        run(g16);
        System.clearProperty("neo.updateMods");
        check("更新後は新しい指定(0.9.3)に追従し、旧0.9.2は削除", has(m16, "sodium-fabric-0.9.3+mc26.3.jar") && !has(m16, "sodium-fabric-0.9.2+mc26.3.jar"));
        MODS.get("iris").get(0).requires.clear();
        MODS.get("iris").get(0).requires.add("SOD092");
        MODS.put("sodium", sodBackup);

        System.out.println("T17: 指定された版が候補に無い場合は、通常のrelease優先に戻る");
        MODS.get("iris").get(0).requires.clear();
        MODS.get("iris").get(0).requires.add("NO-SUCH-ID");
        File g17 = freshDir();
        r = run(g17);
        check("入手不能な指定でも失敗せず、release 0.9.2 を入れる", r.ok() && has(new File(g17, "mods"), "sodium-fabric-0.9.2+mc26.3.jar"));
        MODS.get("iris").get(0).requires.clear();
        MODS.get("iris").get(0).requires.add("SOD092");

        System.out.println("T19: NeoMods の新しい版を出したら、次の起動で入れ替わる(updateMods なし)");
        byte[] oldNeo = neoJar;
        neoJar = jar("neo", "263-new");
        f = GH_FILE_REQ.get();
        r = run(g1);
        check("新しい NeoMod.jar に入れ替わる", sha256(read(new File(m1, "NeoMod.jar"))).equals(sha256(neoJar)) && GH_FILE_REQ.get() == f + 1);
        check("戻り値 true", r.ok());
        neoJar = oldNeo;
        run(g1);

        System.out.println("T18: NeoMods の SHA-256 が合わなければ配置しない");
        neoDigestOverride = "sha256:" + sha256("different".getBytes());
        File g18 = freshDir();
        r = run(g18);
        neoDigestOverride = null;
        check("NeoMod.jar は配置されない・.partも残らない・false", !r.ok() && !has(new File(g18, "mods"), "NeoMod.jar") && noPart(new File(g18, "mods")));
        check("警告に SHA-256 と出る", r.log().contains("SHA-256"));

        System.out.println("T20: リリースの Neo-Client.json が手元と違えば .Neo-Client.json を書き換える");
        File vdir = new File(freshDir(), "versions/.Neo-Client"); vdir.mkdirs();
        File local = new File(vdir, ".Neo-Client.json");
        Files.writeString(local.toPath(), "{\"id\":\".Neo-Client\",\"v\":1}");
        r = runUpdate(vdir);
        check("最新の中身に書き換わる・.part が残らない", java.util.Arrays.equals(read(local), clientJson) && !new File(vdir, "..Neo-Client.json.part").exists());
        r = runUpdate(vdir);
        check("同じなら OK とだけ出る", r.log().contains(".Neo-Client.json: OK"));
        byte[] good = clientJson;
        clientJson = "{\"id\":\"other\"}".getBytes(StandardCharsets.UTF_8);
        runUpdate(vdir);
        check("id が .Neo-Client でなければ書き換えない", java.util.Arrays.equals(read(local), good));
        clientJson = good;
        File noJson = new File(freshDir(), "versions/.Neo-Client"); noJson.mkdirs();
        runUpdate(noJson);
        check("手元に .Neo-Client.json が無ければ作らない", !new File(noJson, ".Neo-Client.json").exists());

        System.out.println("T15: サーバー停止(オフライン)");
        server.stop(0);
        r = run(freshDir());
        check("取得できなくても例外にならない・false", !r.ok() && r.log().contains("4 個"));
        r = run(g1);
        check("取得済みならオフラインでも警告なし・true", r.ok() && !r.log().contains("警告"));
        check("NeoMods は今の版のまま起動すると出る", r.log().contains("今の版のまま"));

        System.out.println();
        System.out.println(fail == 0 ? "ALL PASS (" + pass + ")" : "FAILED: PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
