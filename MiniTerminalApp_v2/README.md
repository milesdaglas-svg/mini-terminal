# Mini Terminal (Android)

A minimal on-device terminal app — runs a real persistent `/system/bin/sh`
shell process on your phone, no server, no internet required.

## Important: what this actually is

This gives you Android's **built-in** shell (`toybox` — `ls`, `cd`, `cat`,
`echo`, `ps`, `mkdir`, etc.). It does **not** give you `bash`, `python`,
`pip`, `apt`, `git`, or any package manager — Android doesn't ship those,
and this app doesn't bundle a Linux userland.

If you want a *real* full Linux environment on your phone (real `bash`,
`python`, `git`, package installs), that's what **Termux** already is —
a mature, free, years-in-the-making project. Install it from F-Droid:
https://f-droid.org/packages/com.termux/

This project is meant as a **starting point you can build on yourself** —
for example, later bundling a `proot`-based root filesystem (Alpine or
Debian) to get a real package manager, which is the technique Termux
itself uses.

## How to build it

1. Install [Android Studio](https://developer.android.com/studio) (free)
2. Open this folder (`MiniTerminalApp/`) as a project — "Open" not "New Project"
3. Let Gradle sync (downloads the Android build tools automatically —
   needs internet the first time)
4. Plug in your phone via USB with USB debugging enabled, or use an emulator
5. Click **Run** (the green triangle) — this installs and launches it directly

## To get a standalone `.apk` file you can share/install manually

In Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
The finished file will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```
Copy that to your phone and open it to install (you'll need to allow
"install unknown apps" for whichever app you transfer it with).

## How it works

- `MainActivity.kt` starts one persistent `ProcessBuilder("/system/bin/sh")`
  process when the app opens, and keeps it alive for the whole session —
  this is what lets `cd` actually change directory for later commands,
  instead of resetting every time.
- A background thread continuously reads the shell's combined stdout/stderr
  and appends it to the screen.
- Typing a command and hitting **Run** (or the send key) writes it straight
  into the shell's stdin.
- The shell process is cleanly killed when the app closes.

## Extending this yourself

- **Command history** — store submitted commands in a list, cycle through
  with up/down, similar to normal terminal behavior.
- **Real package manager** — look into `proot-distro`'s approach: download
  a minimal Alpine/Debian rootfs `.tar.gz`, extract it into the app's
  private storage, then `proot` into it instead of running `/system/bin/sh`
  directly. This is a meaningfully bigger project — it's genuinely most of
  what makes Termux, Termux.
- **Multiple tabs/sessions** — multiple `Process` instances, one per tab.
- **Color/ANSI support** — right now output is rendered as plain text; a
  real terminal look needs an ANSI escape code parser or a library like
  `TerminalView` from the open-source Termux app itself.

## New: git support (via JGit, not a native binary)

- `git init`, `git clone <url> [folder]`, `git status`, `git add <pattern>`,
  `git commit -m "message"`, `git pull`, `git push`, `git log`
- Configure auth before clone/pull/push of a private repo:
  ```
  git config user.name yourusername
  git config credential.token ghp_your_personal_access_token
  ```
  Use a **Personal Access Token**, not your real password — GitHub/GitLab
  don't accept plain passwords for git operations anymore. Token is kept
  in memory only, cleared when the app closes (not saved to disk).
- This uses [JGit](https://www.eclipse.org/jgit/) — a pure-Java git
  implementation, roughly 5-10MB, no native binary, no bootstrap download.
  That's what keeps this light compared to Termux's full package-manager
  approach.
- Everything else (`ls`, `cd`, `cat`, etc.) still goes to the real
  on-device `/system/bin/sh` shell as before. `cd` is tracked in both
  places so `git` commands run in the folder you'd expect.

## Known limitation of this git integration

JGit implements the git protocol itself in Java — it does not shell out
to a real `git` binary. That means some advanced/obscure git features
(certain merge strategies, submodules, some hooks) may behave slightly
differently than real git. For clone/add/commit/pull/push/status/log —
the commands most people actually use — it's solid and widely used
(IntelliJ/Android Studio's own built-in git support is built on JGit).

## Building via GitHub Actions (no Android Studio needed)

This repo includes `.github/workflows/build.yml`, which builds the APK
automatically on GitHub's own servers every time you push.

**One-time setup:**
1. Create a new repo on GitHub (can be private or public)
2. Push this project to it:
   ```
   git init
   git add .
   git commit -m "initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```
3. On GitHub, go to the **Actions** tab of your repo — you should see a
   "Build APK" workflow run start automatically (triggered by the push).

**Getting the built APK:**
1. Click into the workflow run (green check = success)
2. Scroll down to **Artifacts**
3. Download `mini-terminal-debug-apk` — it's a zip containing `app-debug.apk`
4. Unzip it, transfer `app-debug.apk` to your phone, open it to install
   (you'll need to allow "install unknown apps" for whatever app you
   used to transfer it — Files, Drive, etc.)

**If the build fails:** click the failed step in the Actions log to see
the actual error, and paste it back to me — I can fix the workflow or
the code directly from that.

**To rebuild after making changes:** just `git add . && git commit -m "..." && git push`
— every push re-triggers the build automatically. You can also trigger
it manually from the Actions tab (the `workflow_dispatch` trigger) without
pushing anything, if you just want to rebuild the same code.
