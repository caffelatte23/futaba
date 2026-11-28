# Futaba

個人向けアイデア帳アプリ（Android アプリ＋Chrome 拡張）。「書くまで0秒。整理はあとで、しなくてもいい。」

- 画面デザイン: [Futaba UX・画面デザイン](https://claude.ai/artifact/XNVpYyrRaFq4GFHVAdE2gy)
- 進行: [Project「Futaba」](https://github.com/users/caffelatte23/projects/1)・Milestone・Issue
- 変更履歴: [CHANGELOG.md](CHANGELOG.md)

## できること

- 開いたらすぐ書ける入力欄。新しい順に流れる一覧、日付区切り
- `#ラベル` で分類（1件に複数）。ドロワーでラベル別・ピン留め・ラベルなしを表示
- 全文検索、複数選択でラベル付け・削除、30日間の「最近削除したもの」
- ホーム画面ウィジェット、クイック設定タイル、共有シートから追加
- Chrome 拡張: ポップアップで書く、右クリック「Futabaに追加」で選択文字とページ URL を追加
- Google アカウントで端末とブラウザの間を同期。オフラインでも書け、つながったときに送られる

## 構成

| ディレクトリ | 内容 |
|---|---|
| `android/` | Android アプリ（Kotlin・Jetpack Compose・Material 3・Hilt・Navigation 3・Glance） |
| `extension/` | Chrome 拡張（Manifest V3・WXT・TypeScript） |
| `firebase/` | Firestore のセキュリティルールとそのテスト |
| `spec/` | Android と拡張で共有するテストケース（`#ラベル` の判定） |
| `scripts/` | 検査・Gradle 起動の補助 |

バックエンドは Firebase（Auth・Firestore、Spark 無料プラン）。端末内 DB は持たず、Firestore のオフラインキャッシュを手元の正本にする。

## セットアップ

リポジトリのルートで `mise.toml` を確認してから実行する。

```sh
mise trust ./mise.toml
mise install
mise exec -- pnpm install
```

Firebase を初めて利用するときは `mise exec -- firebase login` でログインする。

秘密情報はリポジトリに入れない（gitignore 済み）: `android/app/google-services.json`、`extension/.env`、`android/keystore.properties` と署名鍵。

## 検査

GitHub Actions は使わない。lefthook の pre-push で `pnpm check`（ktlint・detekt・Android Lint・単体テスト・oxlint・tsc・ルールテスト）が走る。端末とエミュレータを使う検査は `pnpm check:device`。

## 配布

- Android: 署名済みリリース APK を端末へ直接インストール（`android/keystore.properties` で署名）
- Chrome 拡張: `pnpm -C extension build` の出力 `extension/.output/chrome-mv3` をパッケージ化せずに読み込む

## 開発の進め方

1 Issue ＝ 1 ブランチ、main へは PR 経由。手順は `AGENTS.md` と `.claude/skills/`。設計メモと実装計画（`docs/`）は手元だけで管理し、リポジトリには入れない。
