# Futaba

個人向けアイデア帳アプリ（アプリ名 Futaba ＝ 双葉）。Android アプリ＋Chrome 拡張。

## 読むもの

- 概要・構成: `README.md`、変更履歴: `CHANGELOG.md`
- 要件は Issue 本文（計画の Task の全文を載せてある）。Milestone ＝ バージョン、Project「Futaba」https://github.com/users/caffelatte23/projects/1
- 画面デザインの正本: https://claude.ai/artifact/XNVpYyrRaFq4GFHVAdE2gy
- 設計メモ・実装計画は git 管理外（`docs/` は gitignore）。リポジトリ本体の `C:/Users/Caffe/Desktop/Scripts/mobile/idea-book-app/docs/design.md` と `C:/Users/Caffe/Desktop/Scripts/mobile/idea-book-app/docs/plans/` にだけある。別の作業フォルダからはこの絶対パスで読む。書き換えるのは調整役だけ
- 使うライブラリ・ディレクトリ構成は計画の Tech Stack / ディレクトリ構成節に従う。そこに無いライブラリを足すときは理由を PR／コミット本文に書く
- **作業手順は skills にある。作業前に該当するものを必ず読む**（Codex も `.claude/skills/<name>/SKILL.md` をファイルとして読むこと）
  - 実装: `.claude/skills/futaba-task/SKILL.md`
  - レビュー: `.claude/skills/futaba-review/SKILL.md`
  - 調整役: `.claude/skills/futaba-controller/SKILL.md`

## 検査（CI の代わり）

- 完了と言う前に `pnpm check` を通し、その出力を報告に残す（lefthook の pre-push でも走る）。端末の検査（`pnpm check:device`）は調整役がまとめて走らせる
- 開発ツールは `mise.toml` で固定（`mise install`）。グローバルインストールや `npx` は使わない
- `git commit/push --no-verify` で検査を飛ばさない
- `node scripts/gradlew.mjs --stop` を実装担当は実行しない（全作業フォルダの Gradle を止めてしまう）
- GitHub Actions は使わない（`.github/workflows/` を作らない）
- 静的に検査できる規約（書式・命名・Compose の作法・未使用・アクセシビリティ・型・drawable の色）はこのファイルや skills に書かず、ktlint / detekt / Android Lint / oxlint / tsc / `scripts/` の検査に足す。ここには検査できないことだけを書く

## 進め方

- 1 Task ＝ 1 ブランチ `task/<N>-<slug>`、計画の Step 順に TDD。失敗するテストの実行結果（RED）を先に出し、報告に残す
- main へは GitHub の PR 経由でのみ入れる。実装エージェントは自分のブランチにコミットするだけで、push・PR 作成・マージは調整役が行う
- エミュレータ・Firebase エミュレータは共有資源。使ってよいと指示されたときだけ使う
- Firebase コンソール等の手作業 Step に来たら止めて報告する（推測で進めない）
- 利用者から見える変更は `CHANGELOG.md` の Unreleased に書く（同じ PR で）。設計を変えたら調整役が手元の `docs/design.md` も更新する
- **チケット完了時に skills を見直す（必須）**: そのタスクで繰り返した問題や新しく決めた手順を skills / このファイルに反映するか判断し、反映した場合は同じ PR か直後の PR に含める
