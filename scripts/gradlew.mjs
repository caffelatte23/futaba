import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

const cwd = fileURLToPath(new URL("../android/", import.meta.url));
if (!existsSync(cwd)) {
  console.log("skip: android/ not found");
  process.exit(0);
}
const win = process.platform === "win32";
const quote = (a) => `"${a.replaceAll("\"", "\"\"")}"`;
const args = process.argv.slice(2);
const r = spawnSync(win ? ".\\gradlew.bat" : "./gradlew", win ? args.map(quote) : args, {
  cwd,
  stdio: "inherit",
  shell: win, // .bat needs a shell on Windows
});
process.exit(r.status ?? 1);
