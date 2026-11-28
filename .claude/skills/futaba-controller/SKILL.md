---
name: futaba-controller
description: Futaba の実装を調整役（Opus）として進めるときの手順。タスクの割り振り、Codex/Sonnet の起動、レビューの往復、端末検査の直列化、PR マージ、チケット完了時の skills 見直しまで。
---

# Futaba: 調整役の手順

## 役割分担

- 実装は Sonnet（Claude サブエージェント）と Codex（`codex exec`）。調整役はコードを書かない（衝突の機械的な解消は可）。
- レビューは実装と別モデル。手順は `futaba-review`、実装側は `futaba-task` を必ず読ませる。
- 担当は Issue ラベル `agent:sonnet` / `agent:codex`。進捗台帳は `.superpowers/sdd/v1/progress.md`。

## 1タスクの流れ

1. ブランチを main から作る（Sonnet・文書: リポジトリ本体、Codex: `git worktree add ../futaba-codex`。使い終わった作業フォルダとブランチは削除する。Chrome 拡張は本体の `extension/.output/chrome-mv3` から読み込んでいるので、本体のフォルダは消さない）。
2. brief を抜き出し、指示に「`futaba-task` を読む」「端末を使うか否か」「触ってよい範囲（並行作業との衝突回避）」を必ず書く。
3. Codex は `codex exec --dangerously-bypass-approvals-and-sandbox -C <worktree> -o <final.md> "<prompt>" < /dev/null` をバックグラウンドで実行し（`< /dev/null` が無いと stdin 待ちで止まる）、`-o` のファイルを見張る処理を別に付ける（プロセスが終了しないことがある）。利用上限に達したら時刻を記録し、解除後に再開する。
4. レビュー → CHANGES_REQUESTED なら同じ実装担当に修正（3回まで）、4回目からは別モデルに交代。受け入れる残余リスクは台帳に Ruling として記録し、次のレビューに「再提起しない」と渡す。
5. リリース前（R8 やビルド設定の変更時も）は、`assembleRelease` をデバッグ鍵で仮署名して実機で起動し `adb logcat -b crash` が空であることを確認する（デバッグ版では出ない R8 の不具合があったため、#51）。
6. Firestore・端末に関わる変更は、他のエージェントが端末を使っていないときに調整役が `pnpm check:device` を走らせる（instrumentation の衝突・アプリの再インストールで落ちるため直列化）。
7. main に rebase → `ship.sh`（push で pre-push 検査 → PR 作成 `Closes #N`、実機確認が残るものは `CLOSE=Refs` → レビュー記録をコメント → squash マージ）。
8. **チケット完了時に skills を見直す（必須）**: そのタスクで新しく繰り返し起きた問題・指示を `futaba-task` / `futaba-review` / この skill / AGENTS.md に反映するか判断し、台帳に「skills 見直し: 変更なし / 変更内容」を1行残す。静的に検査できるものは skills ではなく検査（lint・`scripts/`）に足す。

## 運用上の決まり

- `gradlew --stop` は誰も作業していないときに調整役だけが実行。
- エミュレータは必要なときだけ起動（メモリ不足で自動停止される）。ワイヤレスデバッグの実機がつながっていれば実機を使う（`10.0.2.2` を使う `check:device` は実機では動かない）。
- 同時に動かすエージェントは2本まで（15.6GB のメモリでエミュレータ・Gradle・Firestore エミュレータが重なると OS に自動停止される）。
- タスク完了時と自動停止が起きたときに後片付け: 親のいない Firestore エミュレータ（cloud-firestore-emulator の java）・Gradle/Kotlin デーモン・エミュレータを確認し、誰も作業していなければ止める。コマンドラインまで確認し、ユーザーのアプリ（Claude/Codex/ChatGPT デスクトップ等）は止めない。
- `ship.sh` は `CLOSE=Closes` か `CLOSE=Refs` を必ず付けて実行する（未指定ならスクリプトが止まる）。
- `.superpowers/` は gitignore。台帳・brief・report・review はそこに置く。
