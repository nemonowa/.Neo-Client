package com.neo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * .Neo-Client 最小構成ローダー。
 * バニラの起動処理(net.minecraft.client.main.Main)の前段に割り込み、
 * 1) ゲームのフォルダを versions/.Neo-Client へ切り替え(バニラとセーブ・設定・modsを分ける)
 * 2) 初回起動時の案内表示
 * 3) 標準出力/標準エラーの簡易ログ保存
 * を行ってから、本来のMainへ処理を引き継ぐ。
 */
public class NeoClient {

    private static final String CLIENT_NAME = ".Neo-Client";
    // 対象のMinecraftバージョン(26.x系は年ベースの新バージョン表記)
    private static final String TARGET_MC_VERSION = "26.3";
    // 26.1以降はJava 25が必須
    private static final int REQUIRED_JAVA_FEATURE = 25;
    private static final String VANILLA_MAIN_CLASS = "net.minecraft.client.main.Main";
    // Fabric Loader の起動クラス(Fabricのバージョンプロファイルの mainClass と同じ)
    private static final String FABRIC_MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    // true: Fabricが無ければ案内を出して終了 / false: Fabricが無ければバニラで起動
    private static final boolean REQUIRE_FABRIC = true;
    private static final String MARKER_FILE_NAME = ".neoclient_initialized";
    private static final String LOG_DIR_NAME = "neoclient_logs";
    // neoclient_logs に残す過去のログの数(latest.log は別)
    private static final int KEEP_OLD_LOGS = 10;
    private static final String GAME_DIR_ARG = "--gameDir";

    public static void main(String[] args) throws Exception {
        File gameDir = selectGameDir(resolveGameDir(args));
        args = withGameDir(args, gameDir);

        setupLogCapture(gameDir);

        if (checkFirstRun(gameDir)) {
            printFirstRunGuide(gameDir);
        }

        // 最新のリリースのバージョンJSONが手元と違えば書き換える(次の起動でランチャーが新しい neoclient.jar を取る)。
        // 今回の起動には関係しないので、ゲームの起動を待たせないよう裏で行う
        Thread jsonUpdate = new Thread(() -> ModSetup.updateVersionJson(versionDir(gameDir)), "neoclient-json-update");
        jsonUpdate.setDaemon(true);
        jsonUpdate.start();

        checkJavaVersion();

        System.out.println("[" + CLIENT_NAME + "] 対象: Minecraft " + TARGET_MC_VERSION
                + " / 実行中のJava: " + Runtime.version());
        // 前提(バニラ本体・Fabric)の確認。足りなければここで案内を出して終了する
        String entry = selectEntryClass();

        // Fabric経由のときだけ、必須MOD(Fabric API / Sodium / Iris / NeoMods)をmodsへ導入する。
        // Fabric Loaderはこの後のKnotClient内でmodsを読むので、ここで入れておけば間に合う。
        // 失敗しても起動は止めない(MODが無くても、必要とするMODが無い限りゲームは動く)。
        if (FABRIC_MAIN_CLASS.equals(entry)) {
            try {
                if (!ModSetup.ensure(gameDir, TARGET_MC_VERSION)) {
                    System.err.println("[" + CLIENT_NAME + "] 警告: 一部のMODを導入できませんでした。"
                            + "Irisが必要とするSodiumなどが無いと、Fabricが起動時にエラーを出します。");
                }
            } catch (Throwable t) {
                System.err.println("[" + CLIENT_NAME + "] 警告: MOD導入処理で例外: " + t);
            }
        }

        System.out.println("[" + CLIENT_NAME + "] 起動準備完了。Minecraft本体を起動します...");
        invokeMain(entry, args);
    }

    /**
     * 起動前提(バニラ本体・Fabric)がクラスパスにそろっているか確認し、
     * 引き継ぎ先のクラス名を返す。足りなければ案内を出して終了する。
     * ランチャー自体が前提不足で止まった場合はここまで到達しないため、案内は出せない。
     */
    private static String selectEntryClass() {
        if (!classExists(VANILLA_MAIN_CLASS)) {
            System.err.println("[" + CLIENT_NAME + "] エラー: Minecraft " + TARGET_MC_VERSION
                    + " 本体が見つかりません。");
            System.err.println("  公式ランチャーで Minecraft " + TARGET_MC_VERSION
                    + " を一度起動してから、もう一度お試しください。");
            System.exit(1);
        }
        if (classExists(FABRIC_MAIN_CLASS)) {
            System.out.println("[" + CLIENT_NAME + "] Fabric Loader を検出しました。Fabric経由で起動します。");
            return FABRIC_MAIN_CLASS;
        }
        if (REQUIRE_FABRIC) {
            System.err.println("[" + CLIENT_NAME + "] エラー: Fabric Loader が見つかりません。");
            System.err.println("  バージョンJSONのlibrariesにFabricのライブラリが含まれているか、");
            System.err.println("  ライブラリのダウンロードが完了しているか確認してください。");
            System.exit(1);
        }
        System.out.println("[" + CLIENT_NAME + "] Fabric未検出のためバニラで起動します。");
        return VANILLA_MAIN_CLASS;
    }

    private static boolean classExists(String name) {
        try {
            // 初期化はせず、存在だけ確認する
            Class.forName(name, false, NeoClient.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Javaが古い場合に警告を出す。
     * 通常は継承元JSONのjavaVersionによりランチャーが適切なJavaを選ぶが、
     * 手動起動やランチャー設定で上書きされた場合の原因特定用。
     */
    private static void checkJavaVersion() {
        int feature = Runtime.version().feature();
        if (feature < REQUIRED_JAVA_FEATURE) {
            System.err.println("[" + CLIENT_NAME + "] 警告: Minecraft " + TARGET_MC_VERSION
                    + " には Java " + REQUIRED_JAVA_FEATURE + " 以上が必要です(現在: Java "
                    + feature + ")。起動に失敗する可能性があります。");
        }
    }

    /**
     * ランチャーが渡したゲームのフォルダ(.minecraft)の versions/.Neo-Client を、このクライアントのゲームのフォルダにする。
     * セーブ・options.txt・mods・リソースパックがバニラと分かれる。assets・librariesは別の引数なので共有のまま。
     * ランチャー側でゲームのフォルダを指定していて versions/.Neo-Client が無いときは変えない。
     */
    static File selectGameDir(File launcherDir) {
        if (isVersionDir(launcherDir)) return launcherDir;
        File own = versionDir(launcherDir);
        return own.isDirectory() ? own : launcherDir;
    }

    /** --gameDir の値を差し替えた引数を返す(無ければ足す)。元の配列は変えない。 */
    static String[] withGameDir(String[] args, File gameDir) {
        String[] out = args.clone();
        for (int i = 0; i < out.length - 1; i++) {
            if (GAME_DIR_ARG.equals(out[i])) {
                out[i + 1] = gameDir.getPath();
                return out;
            }
        }
        String[] added = java.util.Arrays.copyOf(out, out.length + 2);
        added[out.length] = GAME_DIR_ARG;
        added[out.length + 1] = gameDir.getPath();
        return added;
    }

    /** このクライアント自身のフォルダ(versions/.Neo-Client)。gameDir が既にそこなら gameDir そのもの。 */
    static File versionDir(File gameDir) {
        return isVersionDir(gameDir) ? gameDir : new File(new File(gameDir, "versions"), CLIENT_NAME);
    }

    private static boolean isVersionDir(File dir) {
        File parent = dir.getAbsoluteFile().getParentFile();
        return CLIENT_NAME.equals(dir.getName()) && parent != null && "versions".equals(parent.getName());
    }

    private static File resolveGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (GAME_DIR_ARG.equals(args[i])) {
                return new File(args[i + 1]);
            }
        }
        return new File(System.getProperty("user.dir"));
    }

    private static boolean checkFirstRun(File gameDir) throws IOException {
        File marker = new File(gameDir, MARKER_FILE_NAME);
        if (marker.exists()) {
            return false;
        }
        if (!gameDir.exists()) {
            gameDir.mkdirs();
        }
        Files.write(marker.toPath(),
                ("initialized: " + new Date() + System.lineSeparator()).getBytes());
        return true;
    }

    private static void printFirstRunGuide(File gameDir) {
        System.out.println("========================================================");
        System.out.println(" [" + CLIENT_NAME + "] 初回起動を検出しました");
        System.out.println("--------------------------------------------------------");
        System.out.println(" " + CLIENT_NAME + " を初めて起動しています。");
        System.out.println(" 通常のMinecraftと同じ操作で問題ありませんが、");
        System.out.println(" 以下の点にご注意ください。");
        System.out.println();
        System.out.println(" ・セーブ・設定・modsは " + gameDir.getPath() + " に保存します");
        System.out.println(" ・modsフォルダに Fabric API / Sodium / Iris / NeoMods が無い場合は、起動時に自動で導入します");
        System.out.println("   (Modrinth・GitHubから取得。インターネット接続が必要。導入しない場合は -Dneo.skipModSetup=true)");
        System.out.println(" ・ログは versions/" + CLIENT_NAME + "/" + LOG_DIR_NAME
                + "/latest.log にも保存されます");
        System.out.println(" ・この案内は次回以降表示されません");
        System.out.println("   (解除するには " + gameDir.getPath()
                + File.separator + MARKER_FILE_NAME + " を削除してください)");
        System.out.println("========================================================");
    }

    private static void setupLogCapture(File gameDir) throws IOException {
        // ログは versions/.Neo-Client/ (このバージョン自身の名前空間)の中に置く
        File logDir = new File(versionDir(gameDir), LOG_DIR_NAME);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        File logFile = new File(logDir, "latest.log");
        // 前回の latest.log は、その日時の名前で残す。古いものは KEEP_OLD_LOGS 個まで
        if (logFile.isFile()) {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(logFile.lastModified()));
            if (!logFile.renameTo(new File(logDir, stamp + ".log"))) {
                Files.deleteIfExists(logFile.toPath());
            }
        }
        File[] old = logDir.listFiles((d, n) -> n.endsWith(".log") && !n.equals("latest.log"));
        if (old != null && old.length > KEEP_OLD_LOGS) {
            java.util.Arrays.sort(old, java.util.Comparator.comparing(File::getName));
            for (int i = 0; i < old.length - KEEP_OLD_LOGS; i++) {
                Files.deleteIfExists(old[i].toPath());
            }
        }

        // 標準出力と標準エラーの両方から同じファイルへ書くので、書き込みは1つのロックでまとめる
        OutputStream file = new java.io.BufferedOutputStream(new FileOutputStream(logFile));
        PrintStream teeOut = new PrintStream(new TeeOutputStream(System.out, file), true);
        PrintStream teeErr = new PrintStream(new TeeOutputStream(System.err, file), true);

        System.setOut(teeOut);
        System.setErr(teeErr);

        System.out.println("[" + CLIENT_NAME + "] ログ保存先: " + logFile.getAbsolutePath());
    }

    private static void invokeMain(String className, String[] args) throws Exception {
        Class<?> mainClass = Class.forName(className);
        Method mainMethod = mainClass.getMethod("main", String[].class);
        try {
            mainMethod.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // ゲーム側の例外を、リフレクションの包みを外してそのまま投げる(クラッシュの原因が読みやすいように)
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    /** コンソールとログファイルの両方へ書く。ファイルは標準出力と標準エラーで共有するので、全体を1つのロックで守る */
    private static class TeeOutputStream extends OutputStream {
        private static final Object LOCK = new Object();
        private final OutputStream[] streams;

        TeeOutputStream(OutputStream... streams) {
            this.streams = streams;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (LOCK) {
                for (OutputStream s : streams) s.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (LOCK) {
                for (OutputStream s : streams) s.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (LOCK) {
                for (OutputStream s : streams) s.flush();
            }
        }
    }
}
