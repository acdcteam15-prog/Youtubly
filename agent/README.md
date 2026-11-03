# Remoty Agent (Windows 11)

1. Install: ZeroTier One (joined to your network), Python 3.10+, Git, Node.js, and Claude Code (run `claude` once to log in).
   For Android builds also install JDK 17+ and the Android SDK, then set `env.ANDROID_HOME` in config.json.
2. Double-click `setup.bat` (asks for admin once; it installs everything and starts the agent). It prints your ZeroTier address and the pairing token.
3. `run-agent.bat` starts it (it also starts at sign-in). Leave the laptop signed in and unlocked, otherwise screenshots are black.

`config.json`: `roots` = the only folders the phone may touch. `claude_allowed_tools` = what Claude Code may run without asking.
`python remoty_agent.py --new-token` rotates the pairing token.
A project can contain `remoty.json`: `{"type": "node", "run": "npm run dev", "kind": "live"}` to override how it is started.
