package neo.client.setup;

import java.io.File;
import neo.client.loader.NeoClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 起動時に mods フォルダへ必須MOD(Fabric API / Sodium / Iris / NeoMods)を導入する。
 *
 * - Fabric Loader は KnotClient の中で mods を読むので、その前(NeoClientの引き渡し前)に実行する
 * - Modrinth API で「対象MCバージョン + Fabric」の候補を問い合わせ、release を優先、無ければ beta を使う
 *   (alpha は使わない)。日付が新しくても beta より古い release を選ぶ
 * - ただし他のMODが依存関係(required + version_id)で版を指定していれば、その版を優先する
 *   (IrisがSodiumの特定版を要求するため)
 * - ダウンロードしたjarは API が返す SHA-512 とサイズに一致したときだけ mods に置く
 * - 導入済みかどうかはファイル名ではなく「状態ファイル + jar本体のハッシュ」で判定する
 *   (Modrinthのファイル名は "+mc26.3" のようにMODごとに表記が違うため)
 * - 揃っていてハッシュも一致するなら通信しない(オフラインでも起動できる)
 * - 失敗しても起動は止めない(結果は戻り値で返し、呼び出し側が警告する)
 *
 * システムプロパティ:
 *   neo.skipModSetup=true  この処理をまるごとスキップ
 *   neo.updateMods=true    揃っていても最新版を確認して更新する
 *   neo.modrinthApi        APIのベースURL(既定 https://api.modrinth.com)。テストやミラー用
 *   neo.userAgent          Modrinthに送る User-Agent の上書き
 *   neo.githubApi          GitHub APIのベースURL(既定 https://api.github.com)。テスト用
 *
 * NeoMods は Modrinth ではなく GitHub のリリースから取る。タグが v+MCバージョン(v26.3 など)の
 * 最新のリリースを選び、GitHub が付ける SHA-256 で検証する。起動のたびに最新を確かめ、新しい版が出ていれば入れ替える
 * (確かめられなければ、入れ済みの版のまま起動する)。
 */
public final class ModSetup {

    /**
     * 自動導入するModrinthのプロジェクトslug。いずれもFabricのmod idと一致する。
     * 依存する側(iris)を、依存される側(sodium)より先に並べること。
     * irisが「必須」と指定するsodiumの版に、sodiumの導入版を合わせるため。
     */
    static final String[] REQUIRED_MODS = {"fabric-api", "iris", "sodium"};

    /** GitHubのリリースから導入するMOD。{Fabricのmod id, リポジトリ} */
    static final String[][] GITHUB_MODS = {{"neo", "nemonowa/NeoMod"}};

    private static final String LOADER = "fabric";
    private static final String CLIENT_NAME = ".Neo-Client";
    private static final String STATE_FILE_NAME = "neoclient_mods.properties";
    private static final String TAG = "[.Neo-Client][mods]";
    private static final String DEFAULT_API = "https://api.modrinth.com";
    private static final String CDN_HOST = "cdn.modrinth.com";
    private static final String DEFAULT_GITHUB_API = "https://api.github.com";
    private static final String GITHUB_HOST = "github.com";
    private static final String DEFAULT_USER_AGENT = "neo-client/1.0 (Minecraft Fabric launcher helper)";

    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._+\\-]+\\.jar");
    private static final Pattern HEX_128 = Pattern.compile("[0-9a-fA-F]{128}");
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-fA-F]{64}");

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int API_READ_TIMEOUT_MS = 20_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 60_000;
    private static final long MAX_API_BYTES = 5L * 1024 * 1024;
    private static final long MAX_JAR_BYTES = 100L * 1024 * 1024;

    private ModSetup() {}

    /** 必須MODがすべてそろっていれば true。足りない/失敗があれば false。 */
    public static boolean ensure(File gameDir, String mcVersion) {
        if (Boolean.getBoolean("neo.skipModSetup")) {
            info("MOD自動導入をスキップします(neo.skipModSetup)");
            return true;
        }
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            warn("modsフォルダを作成できません: " + modsDir);
            return false;
        }
        File stateFile = new File(NeoClient.versionDir(gameDir), STATE_FILE_NAME);
        Properties state = loadState(stateFile);
        boolean force = Boolean.getBoolean("neo.updateMods");

        int failed = 0;
        for (String slug : REQUIRED_MODS) {
            try {
                ensureOne(slug, null, mcVersion, modsDir, state, force);
            } catch (Exception e) {
                failed++;
                warn(slug + " を準備できませんでした (" + e + ")");
            }
        }
        for (String[] m : GITHUB_MODS) {
            try {
                ensureOne(m[0], m[1], mcVersion, modsDir, state, force);
            } catch (Exception e) {
                failed++;
                warn(m[0] + " を準備できませんでした (" + e + ")");
            }
        }
        saveState(stateFile, state);
        warnDuplicates(modsDir);
        if (failed > 0) {
            warn(failed + " 個のMODを準備できませんでした。ネットワーク接続を確認してください。"
                    + "(このままゲームは起動を続けます)");
        }
        return failed == 0;
    }

    // ------------------------------------------------------------------ 1 MOD 分

    /** repo が null なら Modrinth、あれば そのGitHubリポジトリのリリースから取る */
    private static void ensureOne(String slug, String repo, String mc, File modsDir, Properties state, boolean force)
            throws Exception {
        String algorithm = repo == null ? "SHA-512" : "SHA-256";
        String hashKey = slug + "." + algorithm.replace("-", "").toLowerCase();
        String curFile = state.getProperty(slug + ".file");
        String curHash = state.getProperty(hashKey);
        boolean sameMc = mc.equals(state.getProperty(slug + ".mc"));

        boolean installed = sameMc && curFile != null && curHash != null && isSafeJarName(curFile)
                && new File(modsDir, curFile).isFile() && curHash.equalsIgnoreCase(digest(new File(modsDir, curFile), algorithm));

        // Modrinthの物は、揃っていてハッシュも一致するなら通信しない(版の組み合わせを勝手に変えないため)
        if (repo == null && !force && installed) {
            info(slug + ": OK (" + curFile + ")");
            return;
        }

        Remote remote;
        if (repo == null) {
            remote = resolveLatest(slug, mc, collectPins(state));
        } else {
            // GitHubの物(自作のMOD)は毎回最新のリリースを確かめる。確かめられなければ今のjarのまま起動する
            try {
                remote = resolveGithub(repo, mc);
            } catch (IOException e) {
                if (!installed) throw e;
                info(slug + ": 最新版を確認できないため、今の版のまま起動します (" + curFile + ", " + e.getMessage() + ")");
                return;
            }
        }
        File target = new File(modsDir, remote.filename);

        // 入れ済みのjarと同じ物なら、ハッシュを計算し直さない
        boolean same = installed && remote.filename.equals(curFile) && remote.hash.equalsIgnoreCase(curHash);
        if (same || (target.isFile() && remote.hash.equalsIgnoreCase(digest(target, remote.hashAlgorithm)))) {
            info(slug + ": OK (" + remote.filename + ")");
        } else {
            info(slug + ": ダウンロードします " + remote.filename
                    + " (" + remote.versionNumber + ", " + remote.versionType
                    + (remote.pinned ? ", 他のMODが必須と指定した版" : "") + ")");
            download(remote, modsDir, target);
            info(slug + ": 完了 " + remote.filename);
        }

        // 自分が管理していた旧ファイルだけを入れ替える(ユーザーの別ファイルには触らない)
        if (curFile != null && !curFile.equals(remote.filename) && isSafeJarName(curFile)) {
            Files.deleteIfExists(new File(modsDir, curFile).toPath());
        }
        state.setProperty(slug + ".file", remote.filename);
        state.setProperty(hashKey, remote.hash.toLowerCase());
        state.setProperty(slug + ".mc", mc);
        state.setProperty(slug + ".version", remote.versionNumber);
        state.setProperty(slug + ".requires", String.join(",", remote.requires));
    }

    // ------------------------------------------------------------------ Modrinth API

    private static final class Remote {
        String filename;
        String url;
        String hash;
        String hashAlgorithm = "SHA-512";
        String versionNumber = "";
        String versionType = "";
        long size = -1;
        /** この版が「必須」と指定している他MODの版ID(Modrinthのdependencies[].version_id) */
        List<String> requires = new ArrayList<>();
        /** 他のMODの指定により選ばれた版か */
        boolean pinned;
    }

    private static Remote resolveLatest(String slug, String mc, java.util.Set<String> pins) throws IOException {
        String url = apiBase() + "/v2/project/" + slug + "/version"
                + "?game_versions=" + enc("[\"" + mc + "\"]")
                + "&loaders=" + enc("[\"" + LOADER + "\"]");

        Object root;
        try {
            root = Json.parse(httpGetString(url));
        } catch (IllegalArgumentException e) {
            throw new IOException("APIの応答を解析できません: " + e.getMessage());
        }
        if (!(root instanceof List)) throw new IOException("APIの応答が想定外です");
        List<Map<String, Object>> versions = castList(root);
        if (versions.isEmpty()) {
            throw new IOException("Minecraft " + mc + " / " + LOADER + " 向けの版が見つかりません");
        }

        // 新しい順に並べる
        versions.sort(Comparator.comparing(
                (Map<String, Object> m) -> String.valueOf(m.get("date_published"))).reversed());
        // 他のMODが「この版が必須」と指定している場合は、その版を最優先する
        // (例: IrisはSodiumの特定の版を要求する。違う版を入れるとFabricが起動時に失敗しうる)
        Map<String, Object> chosen = null;
        boolean pinned = false;
        for (Map<String, Object> v : versions) {
            if (pins.contains(String.valueOf(v.get("id")))) {
                chosen = v;
                pinned = true;
                break;
            }
        }
        // 指定が無ければ release を優先、無ければ beta。alpha は使わない
        if (chosen == null) chosen = firstOfType(versions, "release");
        if (chosen == null) chosen = firstOfType(versions, "beta");
        if (chosen == null) {
            throw new IOException("Minecraft " + mc + " 向けに release/beta がありません(alphaは使いません)");
        }

        Map<String, Object> file = pickFile(castList(chosen.get("files")));
        if (file == null) throw new IOException("jarファイルが見つかりません");

        Remote r = new Remote();
        r.pinned = pinned;
        r.requires = requiredVersionIds(chosen);
        r.filename = asString(file.get("filename"));
        r.url = asString(file.get("url"));
        Object hashes = file.get("hashes");
        r.hash = hashes instanceof Map ? asString(((Map<?, ?>) hashes).get("sha512")) : null;
        Object size = file.get("size");
        if (size instanceof Number) r.size = ((Number) size).longValue();
        String vn = asString(chosen.get("version_number"));
        if (vn != null) r.versionNumber = vn;
        String vt = asString(chosen.get("version_type"));
        if (vt != null) r.versionType = vt;

        if (!isSafeJarName(r.filename)) {
            throw new IOException("安全でないファイル名のため拒否しました: " + r.filename);
        }
        if (r.hash == null || !HEX_128.matcher(r.hash).matches()) {
            throw new IOException("SHA-512が取得できないため、検証できないファイルは入れません");
        }
        if (r.url == null || !isAllowedDownloadUrl(r.url)) {
            throw new IOException("想定外のダウンロード元のため拒否しました: " + r.url);
        }
        if (r.size > MAX_JAR_BYTES) {
            throw new IOException("ファイルが大きすぎます: " + r.size);
        }
        return r;
    }

    // ------------------------------------------------------------------ GitHub Releases

    private static Remote resolveGithub(String repo, String mc) throws IOException {
        Object root;
        try {
            root = Json.parse(httpGetString(githubApiBase() + "/repos/" + repo + "/releases?per_page=30"));
        } catch (IllegalArgumentException e) {
            throw new IOException("APIの応答を解析できません: " + e.getMessage());
        }
        if (!(root instanceof List)) throw new IOException("APIの応答が想定外です");

        // GitHubは新しい順に返す。下書き・プレリリースは使わない
        Map<String, Object> chosen = null;
        for (Map<String, Object> rel : castList(root)) {
            if (Boolean.TRUE.equals(rel.get("draft")) || Boolean.TRUE.equals(rel.get("prerelease"))) continue;
            String tag = asString(rel.get("tag_name"));
            if (tag != null && (tag.equals("v" + mc) || tag.startsWith("v" + mc + "-"))) {
                chosen = rel;
                break;
            }
        }
        if (chosen == null) throw new IOException("Minecraft " + mc + " 向けのリリースが見つかりません");

        Map<String, Object> asset = null;
        for (Map<String, Object> a : castList(chosen.get("assets"))) {
            if (String.valueOf(a.get("name")).toLowerCase().endsWith(".jar")) {
                asset = a;
                break;
            }
        }
        if (asset == null) throw new IOException("jarファイルが見つかりません");

        Remote r = new Remote();
        r.filename = asString(asset.get("name"));
        r.url = asString(asset.get("browser_download_url"));
        r.hashAlgorithm = "SHA-256";
        String digest = asString(asset.get("digest"));
        r.hash = digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null;
        Object size = asset.get("size");
        if (size instanceof Number) r.size = ((Number) size).longValue();
        r.versionNumber = asString(chosen.get("tag_name"));
        r.versionType = "release";

        if (!isSafeJarName(r.filename)) {
            throw new IOException("安全でないファイル名のため拒否しました: " + r.filename);
        }
        if (r.hash == null || !HEX_64.matcher(r.hash).matches()) {
            throw new IOException("SHA-256が取得できないため、検証できないファイルは入れません");
        }
        if (r.url == null || !isAllowedGithubUrl(r.url, repo)) {
            throw new IOException("想定外のダウンロード元のため拒否しました: " + r.url);
        }
        if (r.size > MAX_JAR_BYTES) {
            throw new IOException("ファイルが大きすぎます: " + r.size);
        }
        return r;
    }

    private static String githubApiBase() {
        String v = System.getProperty("neo.githubApi");
        String base = (v == null || v.trim().isEmpty()) ? DEFAULT_GITHUB_API : v.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 通常は https://github.com/<repo>/releases/download/ のみ。APIを上書きした場合に限り、その同一ホストも許可する(テスト用)。 */
    private static boolean isAllowedGithubUrl(String urlText, String repo) {
        try {
            URL u = URI.create(urlText).toURL();
            String host = u.getHost();
            if ("https".equalsIgnoreCase(u.getProtocol()) && GITHUB_HOST.equalsIgnoreCase(host)
                    && u.getPath().startsWith("/" + repo + "/releases/download/")) return true;
            String override = System.getProperty("neo.githubApi");
            if (override != null && !override.trim().isEmpty()) {
                return host != null && host.equalsIgnoreCase(URI.create(override.trim()).toURL().getHost());
            }
            return false;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ バージョンJSONの更新

    /** .Neo-Client 自身のリポジトリ。最新のリリースに Neo-Client.json(versions/.Neo-Client/.Neo-Client.json の中身)を付ける */
    static final String CLIENT_REPO = "nemonowa/.Neo-Client";
    private static final String CLIENT_JSON_ASSET = "Neo-Client.json";
    private static final long MAX_JSON_BYTES = 1024 * 1024;

    /**
     * 最新のリリースの Neo-Client.json が手元の .Neo-Client.json と違えば書き換える。
     * 書き換えは次の起動から効く(ランチャーが新しいSHA1を見て neoclient.jar を取り直す)。
     * 失敗しても何もしない(今のJSONのまま起動を続ける)。
     */
    public static void updateVersionJson(File versionDir) {
        File local = new File(versionDir, CLIENT_NAME + ".json");
        if (!local.isFile()) return; // ランチャーのversionsの外で動いている時は触らない
        try {
            Object root = Json.parse(httpGetString(githubApiBase() + "/repos/" + CLIENT_REPO + "/releases/latest"));
            if (!(root instanceof Map)) throw new IOException("APIの応答が想定外です");
            Map<String, Object> asset = null;
            for (Map<String, Object> a : castList(((Map<?, ?>) root).get("assets"))) {
                if (CLIENT_JSON_ASSET.equals(a.get("name"))) asset = a;
            }
            if (asset == null) throw new IOException(CLIENT_JSON_ASSET + " がリリースにありません");
            String url = asString(asset.get("browser_download_url"));
            String digest = asString(asset.get("digest"));
            String hash = digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null;
            if (hash == null || !HEX_64.matcher(hash).matches()) throw new IOException("SHA-256が取得できません");
            if (url == null || !isAllowedGithubUrl(url, CLIENT_REPO)) throw new IOException("想定外のダウンロード元です: " + url);
            if (hash.equalsIgnoreCase(digest(local, "SHA-256"))) {
                info(CLIENT_NAME + ".json: OK");
                return;
            }

            byte[] body = httpGetBytes(url, MAX_JSON_BYTES);
            if (!hex(MessageDigest.getInstance("SHA-256").digest(body)).equalsIgnoreCase(hash)) {
                throw new IOException("SHA-256が一致しません");
            }
            Object json = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(json instanceof Map) || !CLIENT_NAME.equals(((Map<?, ?>) json).get("id"))) {
                throw new IOException("id が " + CLIENT_NAME + " ではありません");
            }
            File part = new File(versionDir, "." + CLIENT_NAME + ".json.part");
            Files.write(part.toPath(), body);
            try {
                Files.move(part.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part.toPath(), local.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part.toPath());
            }
            info(CLIENT_NAME + ".json を最新の版に更新しました(次の起動から有効)");
        } catch (Exception e) {
            info(CLIENT_NAME + ".json の更新を確認できませんでした (" + e.getMessage() + ")");
        }
    }

    private static byte[] httpGetBytes(String url, long max) throws IOException {
        HttpURLConnection c = open(url, DOWNLOAD_READ_TIMEOUT_MS);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " (" + url + ")");
            try (InputStream in = c.getInputStream()) {
                byte[] body = in.readNBytes((int) max + 1);
                if (body.length > max) throw new IOException("ファイルが大きすぎます");
                return body;
            }
        } finally {
            c.disconnect();
        }
    }

    /** 状態ファイルに記録済みの「他MODが必須と指定している版ID」をすべて集める */
    private static java.util.Set<String> collectPins(Properties state) {
        java.util.Set<String> pins = new java.util.HashSet<>();
        for (String slug : REQUIRED_MODS) {
            String v = state.getProperty(slug + ".requires");
            if (v == null) continue;
            for (String id : v.split(",")) {
                if (!id.trim().isEmpty()) pins.add(id.trim());
            }
        }
        return pins;
    }

    /** この版が dependency_type=required かつ version_id 付きで指定している版IDの一覧 */
    private static List<String> requiredVersionIds(Map<String, Object> version) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> d : castList(version.get("dependencies"))) {
            Object id = d.get("version_id");
            if ("required".equals(d.get("dependency_type")) && id instanceof String && !((String) id).isEmpty()) {
                ids.add((String) id);
            }
        }
        return ids;
    }

    private static Map<String, Object> firstOfType(List<Map<String, Object>> sortedVersions, String type) {
        for (Map<String, Object> v : sortedVersions) {
            if (type.equals(v.get("version_type"))) return v;
        }
        return null;
    }

    private static Map<String, Object> pickFile(List<Map<String, Object>> files) {
        Map<String, Object> firstJar = null;
        for (Map<String, Object> f : files) {
            if (!String.valueOf(f.get("filename")).toLowerCase().endsWith(".jar")) continue;
            if (Boolean.TRUE.equals(f.get("primary"))) return f;
            if (firstJar == null) firstJar = f;
        }
        return firstJar;
    }

    private static String apiBase() {
        String v = System.getProperty("neo.modrinthApi");
        String base = (v == null || v.trim().isEmpty()) ? DEFAULT_API : v.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 通常は https://cdn.modrinth.com のみ。APIを上書きした場合に限り、その同一ホストも許可する(テスト用)。 */
    private static boolean isAllowedDownloadUrl(String urlText) {
        try {
            URL u = URI.create(urlText).toURL();
            String host = u.getHost();
            if ("https".equalsIgnoreCase(u.getProtocol()) && CDN_HOST.equalsIgnoreCase(host)) return true;
            String override = System.getProperty("neo.modrinthApi");
            if (override != null && !override.trim().isEmpty()) {
                return host != null && host.equalsIgnoreCase(URI.create(override.trim()).toURL().getHost());
            }
            return false;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readTimeoutMs);
        c.setInstanceFollowRedirects(true);
        String ua = System.getProperty("neo.userAgent");
        c.setRequestProperty("User-Agent", (ua == null || ua.trim().isEmpty()) ? DEFAULT_USER_AGENT : ua.trim());
        return c;
    }

    private static String httpGetString(String url) throws IOException {
        HttpURLConnection c = open(url, API_READ_TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " (" + url + ")");
            try (InputStream in = c.getInputStream()) {
                byte[] body = in.readNBytes((int) MAX_API_BYTES + 1);
                if (body.length > MAX_API_BYTES) throw new IOException("APIの応答が大きすぎます");
                return new String(body, StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** .part に落として ハッシュ とサイズを検証し、一致したときだけ本来の名前に移す。 */
    private static void download(Remote r, File modsDir, File target) throws Exception {
        File part = new File(modsDir, "." + r.filename + ".part");
        MessageDigest md = MessageDigest.getInstance(r.hashAlgorithm);
        long total = 0;
        HttpURLConnection c = open(r.url, DOWNLOAD_READ_TIMEOUT_MS);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " (" + r.url + ")");
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_JAR_BYTES) throw new IOException("ファイルサイズが上限を超えました");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (r.size >= 0 && total != r.size) {
                throw new IOException("サイズ不一致 (期待 " + r.size + " / 実際 " + total + ")");
            }
            if (!hex(md.digest()).equalsIgnoreCase(r.hash)) {
                throw new IOException(r.hashAlgorithm + "が一致しません。破棄しました");
            }
            try {
                Files.move(part.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            c.disconnect();
            Files.deleteIfExists(part.toPath()); // 失敗時の取り残しを掃除(成功時は既に無い)
        }
    }

    // ------------------------------------------------------------------ 重複MODの警告

    /** 同じmod idのjarが複数あるとFabricは起動に失敗する。自動削除はせず警告する。 */
    private static void warnDuplicates(File modsDir) {
        File[] jars = modsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null) return;
        Map<String, List<String>> byId = new LinkedHashMap<>();
        for (File jar : jars) {
            String id = readModId(jar);
            if (id != null) byId.computeIfAbsent(id, k -> new ArrayList<>()).add(jar.getName());
        }
        List<String> ids = new ArrayList<>(List.of(REQUIRED_MODS));
        for (String[] m : GITHUB_MODS) ids.add(m[0]);
        for (String slug : ids) {
            List<String> names = byId.get(slug);
            if (names != null && names.size() > 1) {
                warn("mod '" + slug + "' のjarが複数あります " + names
                        + " 。重複すると起動に失敗するので、不要な方を手動で削除してください。");
            }
        }
    }

    private static String readModId(File jar) {
        try (ZipFile zf = new ZipFile(jar)) {
            ZipEntry e = zf.getEntry("fabric.mod.json");
            if (e == null || e.getSize() > 1024 * 1024) return null;
            try (Reader r = new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                Object o = Json.parse(sb.toString());
                return o instanceof Map ? asString(((Map<?, ?>) o).get("id")) : null;
            }
        } catch (Exception ex) {
            return null; // 壊れたjarや読めないjarは黙って無視
        }
    }

    // ------------------------------------------------------------------ 状態ファイル

    private static Properties loadState(File f) {
        Properties p = new Properties();
        if (f.isFile()) {
            try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                warn("状態ファイルを読めないため無視します: " + e);
                return new Properties();
            }
        }
        return p;
    }

    private static void saveState(File f, Properties p) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
                p.store(w, "managed by .Neo-Client");
            }
        } catch (IOException e) {
            warn("状態ファイルを保存できません: " + e);
        }
    }

    // ------------------------------------------------------------------ 小物

    private static boolean isSafeJarName(String name) {
        return name != null && !name.contains("..") && SAFE_FILENAME.matcher(name).matches();
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String digest(File f, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<?>) o) {
                if (e instanceof Map) out.add((Map<String, Object>) e);
            }
        }
        return out;
    }

    private static void info(String msg) {
        System.out.println(TAG + " " + msg);
    }

    private static void warn(String msg) {
        System.err.println(TAG + " 警告: " + msg);
    }

    // ------------------------------------------------------------------ 最小のJSONパーサ

    /** 依存なしの最小JSONパーサ(Map / List / String / Double / Boolean / null)。ネストは64段まで。 */
    static final class Json {
        private static final int MAX_DEPTH = 64;
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value(0);
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("JSONの末尾に余分な文字があります");
            return v;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSONのネストが深すぎます");
            if (i >= s.length()) throw new IllegalArgumentException("JSONが途中で終わっています");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); expect(':'); ws();
                m.put(k, value(depth + 1));
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("文字列が閉じていません");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("不正なエスケープです");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("不正な \\u エスケープです");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    case '"': case '\\': case '/': sb.append(e); break;
                    default: throw new IllegalArgumentException("不正なエスケープ \\" + e);
                }
            }
        }

        private Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("想定外の文字(位置 " + i + ")");
            return Double.valueOf(s.substring(st, i));
        }

        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        private void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("'" + c + "' が必要です(位置 " + i + ")");
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
