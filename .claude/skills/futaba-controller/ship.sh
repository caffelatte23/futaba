#!/usr/bin/env bash
# Usage: ship.sh <worktree> <issue#> <branch> <title> <implementer> <reviewer> <review-file>...
# Push branch (pre-push runs pnpm check), open PR (Closes #N), post reviews as a comment, squash-merge.
set -euo pipefail
: "${CLOSE:?set CLOSE=Closes or CLOSE=Refs}"
WT=$1 N=$2 BR=$3 TITLE=$4 IMPL=$5 REV=$6; shift 6
R=caffelatte23/futaba
cd "$WT"
git push -u origin "$BR"
BODY=$(mktemp)
{
  echo "$CLOSE #$N"
  echo
  echo "- 実装: $IMPL ／ レビュー: $REV（APPROVED）"
  echo "- 検査: lefthook pre-push の \`pnpm check\` 通過"
  echo "- 計画: 手元の docs/plans/（git 管理外）・Issue #$N の本文"
} > "$BODY"
gh pr view "$BR" -R $R >/dev/null 2>&1 || gh pr create -R $R --base main --head "$BR" --title "$TITLE" --body-file "$BODY"
CMT=$(mktemp)
for f in "$@"; do echo "### $(basename "$f")"; echo; cat "$f"; echo; done > "$CMT"
gh pr comment "$BR" -R $R --body-file "$CMT"
gh pr merge "$BR" -R $R --squash --delete-branch
gh pr view "$BR" -R $R --json number,state,mergeCommit --jq '"#\(.number) \(.state) \(.mergeCommit.oid[0:7])"'
