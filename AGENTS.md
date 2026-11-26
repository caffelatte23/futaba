# Futaba

個人向けアイデア帳アプリ（アプリ名 Futaba ＝ 双葉）。Android アプリ＋Chrome 拡張。

## 読むもの

- 設計: `docs/design.md`（画面デザインの正本: https://claude.ai/artifact/XNVpYyrRaFq4GFHVAdE2gy）
- 実装計画: `docs/plans/v1.md`（Task N ＝ Issue #N、親 Issue #19〜#25 ＝ フェーズ、Milestone v1、Project「Futaba」https://github.com/users/caffelatte23/projects/1）
- 使うライブラリ・ディレクトリ構成は計画の Tech Stack / ディレクトリ構成節に従う。そこに無いライブラリを足すときは理由を PR／コミット本文に書く

## 検査（CI の代わり）

- 完了と言う前に `pnpm check` を通す（lefthook の pre-push でも走る）。Firestore を触った Task は `pnpm check:device` も
- `git commit/push --no-verify` で検査を飛ばさない
- GitHub Actions は使わない（`.github/workflows/` を作らない）
- 静的に検査できる規約（書式・命名・Compose の作法・未使用・アクセシビリティ・型）はこのファイルに書かず、ktlint / detekt / Android Lint / oxlint / tsc の設定に足す。ここには検査できないことだけを書く

## 進め方

- 1 Task ＝ 1 ブランチ `task/<N>-<slug>`、計画の Step 順に TDD（失敗するテスト→実装→通す）
- Firebase コンソール等の手作業 Step に来たら止めて報告する（推測で進めない）
- 設計を変えたら `docs/design.md` も更新する
