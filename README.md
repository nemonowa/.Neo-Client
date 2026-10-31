<div align="center">

# 🚀｜.Neo-Client - Minecraft Launcher Profile

**説明：** 艦長が自分用に作った Minecraft の起動構成。<br>公式ランチャーから、Fabric と描画系の Mod が入った 26.3 をワンクリックで起動する。

*Install once. Launch from the official launcher. Mods come by themselves.*

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a)
![Loader](https://img.shields.io/badge/loader-Fabric-dbd0b4)
![Java](https://img.shields.io/badge/Java-25-b07219)
![Platform](https://img.shields.io/badge/platform-Windows-555)

</div>

---

## ❱ 情報【Information】

Minecraft の公式ランチャーのインストール一覧に「.Neo-Client」を足す。<br>
起動すると Fabric 経由で 26.3 を立ち上げ、必要な Mod（Fabric API・Sodium・Iris・NeoMods）が無ければ自動で入れる。

```text
Name     : .Neo-Client
Version  : 1.0
Requires : Minecraft 26.3 / Java 25 / 公式ランチャー
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

**json を1つ置くだけで使える。**

1. ランチャーを終了する
2. [Releases](../../releases) から `Neo-Client.zip` をダウンロードして展開する
3. 中の `.Neo-Client.json` を、次の場所に置く（フォルダが無ければ作る）

   ```text
   %APPDATA%\.minecraft\versions\.Neo-Client\.Neo-Client.json
   ```

4. ランチャーを起動し、インストール一覧の「.Neo-Client」で起動する

Minecraft 26.3・Fabric Loader・`neoclient.jar` は、起動した時にランチャーが自動でダウンロードする。

<details>
<summary>インストーラーを使う場合</summary>

`Neo-Client-install.zip` を展開して `install.bat` を実行する（「OK: ...」と出れば成功）。json と jar を手元から `%APPDATA%\.minecraft` へ写すので、jar のダウンロードが要らない。

</details>

初めて起動した時だけ、コンソールに注意書きを出す。

自分でビルドする場合（JDK 21 以上）：

```text
javac --release 21 -encoding UTF-8 -d out src/com/neo/*.java
jar --create --file neoclient.jar -C out .
```

テスト（ローカルの仮サーバーで Mod の取得を確かめる。最後に `ALL PASS`）：

```text
javac -encoding UTF-8 -cp out -d out test/*.java
java -Dtest.root=<空のフォルダ> -cp out com.neo.ModSetupTest
java -Dtest.root=<空のフォルダ> -cp out com.neo.NeoClientTest
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

- Minecraft は Mojang の商標。.Neo-Client は Mojang・Microsoft と関係がない。
- 自動で入れる Mod のうち Fabric API・Sodium・Iris は、それぞれの作者の物。各 Mod のライセンスに従う。

---

## ❱ 連繫【Link】

- **Download：** [Releases](../../releases)
- **Mod：** [NeoMod](https://github.com/nemonowa/NeoMod)
