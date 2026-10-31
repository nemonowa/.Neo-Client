<div align="center">

# 🚀｜.Neo-Client - Minecraft Launcher Profile

**説明：** NEXUS専用のMinecraft の特殊バージョン。<br>`.minecraft/versions`に`.Neo-Client/.Neo-Client.json`を展開するだけで、環境構築が自動でできる！

*Install once. Launch from the official launcher. Mods come by themselves.*

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a)
![Loader](https://img.shields.io/badge/loader-Fabric-dbd0b4)
![Java](https://img.shields.io/badge/Java-25-b07219)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-555)

</div>

---

## ❱ 情報【Information】

Minecraft の公式ランチャーのインストール一覧に「.Neo-Client」を足す。<br>
起動すると Fabric 経由で 26.3 を立ち上げ、必要な Mod（Fabric API・Sodium・Iris・NeoMods）が無ければ自動で入れる。

```text
Name     : .Neo-Client
Version  : 1.0
Requires : Minecraft 26.3 / Java 25 / 公式ランチャー
Platform : Windows / macOS / Linux
Loader   : Fabric Loader 0.19.5
Author   : argonaute_nemo
```

---

## ❱ 特徴【Highlights】

- 🖱️ **公式ランチャーのまま**：別のランチャーを入れず、いつものインストール一覧から起動する
- 📦 **Mod を自動で用意**：Fabric API・Sodium・Iris は Modrinth から、NeoMods は GitHub のリリースから、起動時に入れる
- 🛡️ **確かめてから入れる**：取得先は Modrinth と NeoMods のリリースだけ。ハッシュが合わないファイルは捨てる
- 🛟 **止まらない**：Mod を取得できなくても、ゲームはそのまま起動を続ける
- 🔄 **自動で最新に**：リリースを出すと、次の起動から .Neo-Client 本体と NeoMods が最新の版に入れ替わる
- 🗂️ **専用のフォルダ**：セーブ・設定・Mod は `versions\.Neo-Client` に置き、通常の Minecraft とは混ざらない
- 📝 **ログを残す**：起動のたびにログをファイルへ保存する

---

## ❱ 導入【Introduction】

**Windows・macOS・Linux のどれでも使える。** 次の2通りから選ぶ。どちらでも結果は同じ（`versions` に `.Neo-Client.json` が置かれる）。

### あなたの Minecraft フォルダ（`.minecraft`）の場所

| OS | 場所 |
|---|---|
| Windows | `%APPDATA%\.minecraft` |
| macOS | `~/Library/Application Support/minecraft` |
| Linux | `~/.minecraft` |

以下、この場所を `<minecraft>` と書く。

### 方法A：手動で配置する（`Neo-Client.zip`）

1. ランチャーを終了する
2. [Releases](../../releases) から `Neo-Client.zip` をダウンロードし、`<minecraft>/versions` に展開する

   ```text
   <minecraft>/versions/.Neo-Client/.Neo-Client.json
   ```

   上の場所に `.Neo-Client.json`（**先頭の `.` ドット抜け注意！** macOS・Linux のファイラーはドット始まりのファイルを隠すので、隠しファイルの表示をオンにして確かめる）ができていればよい。リリースにある単体の `Neo-Client.json` は自動更新用なので、置かない

3. ランチャーを起動し、インストール一覧の「.Neo-Client」で起動する

Minecraft 26.3・Fabric Loader・`neoclient.jar` は、起動した時にランチャーが自動でダウンロードする。

### 方法B：スクリプトで入れる（`Neo-Client-install.zip`）

ランチャーを終了してから、`Neo-Client-install.zip` を展開してスクリプトを実行する（「OK: ...」と出れば成功）。

| OS | 実行するもの |
|---|---|
| Windows | `install.cmd` をダブルクリック |
| macOS・Linux | ターミナルで `./install.sh`（実行権限が無ければ `chmod +x install.sh`、または `bash install.sh`） |

- json と jar を手元から `<minecraft>` へ写すので、jar のダウンロードが要らない。
- macOS・Linux で `.minecraft` の場所が違う場合は、`./install.sh /path/to/minecraft` のように第1引数で指定する。
- jar が json の sha1・サイズと合わない場合は、何も置かずに止まる。

---

初めて起動した時だけ、コンソールに注意書きを出す。

自分でビルドする場合（JDK 21 以上）：

```text
javac --release 21 -encoding UTF-8 -d out $(find src -name "*.java")
jar --create --file neoclient.jar -C out .
```

テスト（ローカルの仮サーバーで Mod の取得を確かめる。最後に `ALL PASS`）：

```text
javac -encoding UTF-8 -cp out -d out $(find tester -name "*.java")
java -Dtest.root=<空のフォルダ> -cp out neo.client.setup.ModSetupTester
java -Dtest.root=<空のフォルダ> -cp out neo.client.loader.NeoClientTester
```

---

## ❱ 機能【Features】

| 分類 | 内容 |
|---|---|
| 🚀 起動 | Fabric Loader があれば Fabric 経由で、無ければバニラで起動する |
| 📦 Mod | Fabric API・Sodium・Iris を Modrinth から、NeoMods を GitHub のリリース（v26.3 など、Minecraft の版と同じタグ）から取得 |
| 🔒 安全 | 取得先は Modrinth と NeoMods のリリースだけ・SHA-512 / SHA-256 で検証・大きすぎるファイルは入れない |
| ☕ Java | Java 25 未満で起動すると警告を出す |
| 🗂️ フォルダ | セーブ・`options.txt`・`mods`・リソースパックは `%APPDATA%\.minecraft\versions\.Neo-Client\` に保存。音などのアセットとライブラリは通常の Minecraft と共有 |
| 📝 ログ | `versions/.Neo-Client/neoclient_logs/` に保存（最新は `latest.log`） |

---

## ❱ 設定【Options】

ランチャーの「JVM 引数」に足して使う。

| 引数 | 動作 |
|---|---|
| `-Dneo.skipModSetup=true` | Mod の自動導入をしない |
| `-Dneo.updateMods=true` | Fabric API・Sodium・Iris を最新の版に更新する（NeoMods は起動のたびに最新の版を確かめる） |

---

## ❱ 困った時【Troubleshooting】

- **「Minecraft 26.3 本体が見つかりません」**：ランチャーで 26.3 を一度起動してから、もう一度起動する
- **「Fabric Loader が見つかりません」**：ランチャーがライブラリをダウンロードし終えているか確かめる
- **起動に失敗する**：1回だけ起動して失敗させ、`launcher_log.txt` を送ってください

---

## ❱ 権利【License】

- Minecraft は Mojang の商標であり.Neo-Client は Mojang・Microsoft と無関係。
- Fabric API・Sodium・Iris は、各著作者に帰属し Mod のライセンスに従う。

---

## ❱ 連繫【Link】

- **Download：** [Releases](../../releases)
- **Mod：** [NeoMod](https://github.com/nemonowa/NeoMod)
