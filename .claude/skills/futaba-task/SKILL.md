---
name: futaba-task
description: Futaba の実装タスク（Issue / 計画の Task N）を1つ実装するときの手順。実装担当（Sonnet・Codex）が作業開始前に必ず読む。TDD・端末の使い方・検査・報告の決まりを含む。
---

# Futaba: 実装タスクの進め方

このリポジトリで Task を実装するエージェントの手順。過去に繰り返し守られなかった点は **太字**。

## 開始前

1. 渡された brief（Task の全文）、`AGENTS.md`、`docs/design.md` の関係節を読む。
2. 既存コードの型（ViewModel＋Nav3 entry decorator、vector drawable、FutabaTheme トークン、FakeNoteRepository 等）を確認し、それに合わせる。
3. 自分のブランチ `task/<N>-<slug>` にいることを確認。**push・PR・マージはしない**（調整役の仕事）。

## TDD（必須）

- **テストを先に書き、失敗する実行結果（RED）を報告に貼る。** 実装後に初めて走らせたテストは RED と認めない。
- コンパイルエラーの RED は、テストに意味のある assert が書かれている場合のみ可。可能なら assert で落ちる形にする。
- 非同期・Flow のテストは「**検証したい状態そのものを await してから assert**」（例: サイズだけ await して labelIds を assert しない）。
- 1 回の呼び出しであることが契約なら、呼び出し回数・引数まで assert する。

## よく踏んだ設計の落とし穴

- **入力中テキストを失わない**: 編集状態は ViewModel が単一の所有者。初回の読み込み（hydrate）を1回だけ行い、それまで入力を受け付けない。保存は「ユーザー操作の時点で採番した seq」で順序付け（`core/data/DraftStore` を参照）。画面側から渡すテキストは「その欄が初期化済みのときだけ」。
- **外から届いた変更は置き換えでなく追記**（共有・同期で入力欄を上書きしない）。
- **画面の一時状態**（シートの対象、選択、取り消し）は ViewModel かシングルトンに置き、再生成・削除・復元で漏れたり復活したりしないこと。
- **Firestore の読み込み直後の空や一時的な値**で破壊的な処理（フォールバック・削除）をしない。uid 確定＋データ到着を条件にする。
- suspend の repository 呼び出しは失敗し得る（未ログイン等）。UI では CancellationException 以外を捕まえて表示し、入力は保持する。
- メインスレッドで全件処理しない（`flowOn(Dispatchers.Default)`、入力は debounce）。

## 検査・ビルド

- 完了前に `pnpm check` を通し、**その出力（末尾の BUILD 行・テスト件数）を報告に貼る**。
- **`node scripts/gradlew.mjs --stop` を実行しない**（Gradle の常駐プロセスは全作業フォルダで共有されており、他のエージェントの検査を落とす）。常駐プロセスが壊れた場合は `--no-daemon` で回避し、報告に書く。
- 端末（エミュレータ）と Firebase エミュレータは全エージェントで共有。**使ってよいと指示されたときだけ使う**。使う場合も `adb` で他の instrumentation が動いていたら 90 秒待って再試行し、他のプロセスを止めない。`pnpm check:device` は調整役がまとめて走らせる。
- `--no-verify` 禁止。静的に検査できる規約は lint 設定へ（AGENTS.md ではなく）。
- **`firebase-debug.log` / `firestore-debug.log` を表示（cat・抜粋・grep 結果の出力）しない。** Firebase CLI はプロセスの環境変数を丸ごと書き出すため、API キー等の秘密情報が漏れる。ポート競合などの調査は `netstat` やプロセス一覧で行う。環境変数（`env`・`printenv`・`$env:`）も表示しない。

## 報告

指定された report ファイルに、実装内容・RED/GREEN の証拠・`pnpm check` の出力・変更ファイル・自己レビュー・懸念を書く。返信は短い定型（Status / commits / テスト要約 / 懸念 / report パス）。手作業が必要な Step に来たら推測で進めず止めて報告する。
