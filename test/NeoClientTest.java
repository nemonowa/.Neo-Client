package com.neo;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * NeoClient のゲームのフォルダの切り替えのテスト。
 * 実行: java -Dtest.root=<空の作業dir> -cp out com.neo.NeoClientTest   (最後に ALL PASS)
 */
public class NeoClientTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory(new File(System.getProperty("test.root")).toPath(), "mc").toFile();
        File own = new File(root, "versions/.Neo-Client");
        own.mkdirs();

        System.out.println("T1: .minecraft を渡されたら versions/.Neo-Client に切り替える");
        check("切り替え先", NeoClient.selectGameDir(root).equals(own));
        check("versionDir は自分自身", NeoClient.versionDir(own).equals(own));
        check("元の .minecraft からの versionDir", NeoClient.versionDir(root).equals(own));

        System.out.println("T2: versions/.Neo-Client が無い(ランチャーで別のフォルダを指定した)なら変えない");
        File custom = Files.createTempDirectory(root.toPath(), "custom").toFile();
        check("変えない", NeoClient.selectGameDir(custom).equals(custom));

        System.out.println("T3: 引数の --gameDir を差し替える(無ければ足す)");
        String[] in = {"--username", "a", "--gameDir", root.getPath(), "--assetsDir", "x"};
        String[] out = NeoClient.withGameDir(in, own);
        check("値だけ差し替え、ほかはそのまま", Arrays.equals(out,
                new String[]{"--username", "a", "--gameDir", own.getPath(), "--assetsDir", "x"}));
        check("元の配列は変えない", in[3].equals(root.getPath()));
        check("--gameDir が無ければ末尾に足す", Arrays.equals(NeoClient.withGameDir(new String[]{"--demo"}, own),
                new String[]{"--demo", "--gameDir", own.getPath()}));
        check("--gameDir は1つだけ", Arrays.stream(out).filter("--gameDir"::equals).count() == 1);

        System.out.println();
        System.out.println(fail == 0 ? "ALL PASS (" + pass + ")" : "FAILED: PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
