# Futaba

個人向けアイデア帳アプリ（Android アプリ＋Chrome 拡張）。設計は [docs/design.md](docs/design.md)、進行は [Project「Futaba」](https://github.com/users/caffelatte23/projects/1) を参照。検査は GitHub Actions ではなくローカルの `pnpm check`（lefthook）で行う。

## セットアップ

リポジトリのルートで `mise.toml` を確認してから実行する。

```sh
mise trust ./mise.toml
mise install
mise exec -- pnpm install
```

Firebase を初めて利用するときは `mise exec -- firebase login` でログインする。
