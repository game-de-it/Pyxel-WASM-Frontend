# pwf

**Android で Pyxel のゲームを動かす。ブラウザもサーバーもネットワークも要りません。**

[English](README.md) · [日本語](README.ja.md)

[Pyxel](https://github.com/kitao/pyxel) にはブラウザで動かす仕組みがあり、
`.pyxapp` はたいていその形で配られています。pwf は同じランタイム（Pyodide と
Pyxel の wasm wheel）を Android アプリに詰め、その前にライブラリを置いたものです。
`.pyxapp` を端末に置いて、押すだけです。

![ライブラリ](docs/images/library-list.png)

## できること

- **オフラインで動く。** Pyodide も wheel も APK に同梱し、仮想オリジンから
  配信します。WebView が外部 URL を読み込むことはありません。
- **ライブラリ。** 1本ずつ、フォルダごと一括、あるいは URL から追加できます。
  `.pyxapp` を新しいビルドで上書きすれば自動で反映され、エントリもセーブも
  そのままです。
- **assets フォルダを持つゲーム。** そのゲームのフォルダを結び付けられます。
  数百 MB でも起動は重くなりません。ゲームが要求したファイルだけを読むからです。
- **消えないセーブ。** 実行のあいだ進行を保ち、zip で書き出して戻せます。
  ファイルではなくブラウザの保存領域を使うゲームの分も含みます。
- **すべてコントローラーで操作できます。** ランチャー自体も、
  *touch to start* の画面もです。
- **ゲームごとに Pyxel の版を選べます。** 2.x 系のすべてから選べ、端末に無い版は
  その場で取得します。特定の版でしか安定しないゲームのためです。
- **ランタイムのネットワーク更新。** 検証付き、原子的に入れ替え、起動に失敗すれば
  自動で巻き戻します。

## 動作環境

Android 8.0（API 26）以降。ただし実際の制約は OS よりも **WebView** です。
Pyodide は相応に新しいエンジンを要求し、WebView は Android とは別に更新されます。
開発と検証は Android 14 ／ WebView 151 で行いました。

## インストール

[Releases](../../releases) から APK を入れるか、自分でビルドしてください。

```sh
sh tools/fetch-runtime.sh                                        # 一度だけ
python3 tools/make-bundle.py 4 android/app/src/main/assets/runtime
python3 tools/make-catalog.py android/app/src/main/assets/pyxel-catalog.json
cd android && ./gradlew :app:assembleRelease
```

最初のスクリプトが Pyxel と Pyodide のリリースを取得します。このリポジトリに
それらは含めていません。

## ドキュメント

| | |
| --- | --- |
| [使い方](docs/usage.ja.md) · [English](docs/usage.md) | ゲームの追加、遊び方、設定のすべて |
| [開発者向け](docs/development.ja.md) · [English](docs/development.md) | 構成、ビルド、そうなっている理由 |

同じ Web 層をデスクトップのブラウザで動かす
[プロトタイプ](prototype/README.md)もあります。変更を試したり、Pyxel の版を
比べたりするにはこちらが早いです。

## ライセンス

pwf は [MIT](LICENSE) です。

第三者のコンポーネントをそれぞれのライセンスのもとで同梱・再配布しています
（Pyxel は MIT、Pyodide は MPL-2.0）。詳細は
[THIRD-PARTY.md](THIRD-PARTY.md) を参照してください。

## 謝辞

[Pyxel](https://github.com/kitao/pyxel)（北尾崇氏）と
[Pyodide](https://github.com/pyodide/pyodide)。pwf はその周りに置いたランチャーで、
中身は彼らの仕事です。
