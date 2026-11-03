"""
Remoty Agent as a Windows app: a small window with the pairing details, a tray icon, and "run in background".
Build the .exe with build-exe.bat. Closing the window keeps the agent running in the tray.
Start hidden with:  RemotyAgent.exe --background
"""
import base64
import ctypes
import io
import json
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

FROZEN = getattr(sys, "frozen", False)
BACKGROUND = "--background" in sys.argv
NOWIN = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
HOME_DIR = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent

# A windowed exe has no console: give print() and logging a file to write to before anything else loads.
_data = Path(os.environ.get("REMOTY_DATA") or (HOME_DIR / "data"))
_data.mkdir(parents=True, exist_ok=True)
if sys.stdout is None or sys.stderr is None:
    _logfile = open(_data / "agent.log", "a", buffering=1, encoding="utf-8")
    if sys.stdout is None:
        sys.stdout = _logfile
    if sys.stderr is None:
        sys.stderr = _logfile


def already_running() -> bool:
    if os.name != "nt":
        return False
    k = ctypes.windll.kernel32
    global _mutex
    _mutex = k.CreateMutexW(None, False, "Local\\RemotyAgentSingleton")
    return k.GetLastError() == 183  # ERROR_ALREADY_EXISTS


if already_running():
    ctypes.windll.user32.MessageBoxW(0, "Remoty Agent is already running.\nLook for its icon near the clock.", "Remoty Agent", 0x40)
    sys.exit(0)

try:  # crisp text on high-DPI screens
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

import tkinter as tk  # noqa: E402
from tkinter import messagebox  # noqa: E402

import pystray  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import remoty_agent as ra  # noqa: E402
from remoty_logo import render as make_image  # noqa: E402

BG, CARD, BORDER, ACCENT = "#070B14", "#0F1628", "#1E2D50", "#E53935"
TEXT, DIM, GREEN, RED, AMBER = "#F0F4FF", "#8892B0", "#00E676", "#FF5252", "#FFB300"
FW_NAME = "Remoty Agent"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


# ----------------------------------------------------------------------------- Windows helpers
def autostart_enabled() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, "RemotyAgent")
            return True
    except Exception:
        return False


def set_autostart(on: bool):
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            if FROZEN:
                cmd = f'"{sys.executable}" --background'
            else:
                cmd = f'"{Path(sys.executable).with_name("pythonw.exe")}" "{Path(__file__).resolve()}" --background'
            winreg.SetValueEx(k, "RemotyAgent", 0, winreg.REG_SZ, cmd)
            # the older setup.ps1 registered a scheduled task as well: remove it so nothing starts twice
            subprocess.run(["schtasks", "/Delete", "/TN", "RemotyAgent", "/F"], capture_output=True, creationflags=NOWIN)
        else:
            try:
                winreg.DeleteValue(k, "RemotyAgent")
            except OSError:
                pass


def firewall_ok() -> bool:
    try:
        r = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={FW_NAME}"],
                           capture_output=True, text=True, creationflags=NOWIN)
        return r.returncode == 0
    except Exception:
        return False


def add_firewall():
    """Allows this program through Windows Firewall for every port (the web preview uses a random one). Needs admin: UAC asks."""
    prog = sys.executable
    params = (f'/c netsh advfirewall firewall delete rule name="{FW_NAME}" & '
              f'netsh advfirewall firewall add rule name="{FW_NAME}" dir=in action=allow program="{prog}" '
              f'enable=yes profile=any protocol=TCP')
    ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", params, None, 0)


# ----------------------------------------------------------------------------- the app
class App:
    def __init__(self):
        self.q: "queue.Queue" = queue.Queue()
        self.server = None
        self.thread = None
        self.server_error = None
        self.root = tk.Tk()
        self.root.title("Remoty Agent")
        self.root.configure(bg=BG)
        self.sf = self.root.winfo_fpixels("1i") / 96.0      # Windows display scaling (1.0 = 100%)
        w, h = int(540 * self.sf), int(min(700 * self.sf, self.root.winfo_screenheight() * 0.88))
        self.root.geometry(f"{w}x{h}")
        self.root.minsize(int(470 * self.sf), int(380 * self.sf))
        self.root.resizable(True, True)
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        try:
            buf = io.BytesIO()
            make_image(64).save(buf, "PNG")
            self.root.iconphoto(True, tk.PhotoImage(data=base64.b64encode(buf.getvalue())))
        except Exception:
            pass
        self.v = {k: tk.StringVar() for k in ("status", "zt", "token", "git", "fw", "folder", "jobs")}
        self.labels = {}
        self.build_ui()
        self.start_server()
        self.tray = pystray.Icon("remoty", make_image(), "Remoty Agent", pystray.Menu(
            pystray.MenuItem("Open Remoty Agent", lambda: self.q.put(self.show), default=True),
            pystray.MenuItem("Copy pairing token", lambda: self.q.put(self.copy_token)),
            pystray.MenuItem("Start with Windows", lambda: self.q.put(self.toggle_autostart), checked=lambda item: autostart_enabled()),
            pystray.MenuItem("Quit", lambda: self.q.put(self.quit)),
        ))
        self.tray.run_detached()
        self.pump()
        self.refresh()
        if BACKGROUND:
            self.root.withdraw()
        threading.Thread(target=self.first_run_firewall, daemon=True).start()

    # ---- ui
    def row(self, parent, title, var, key, button=None, mono=False):
        f = tk.Frame(parent, bg=CARD, highlightbackground=BORDER, highlightthickness=1)
        f.pack(fill="x", pady=4)
        tk.Label(f, text=title, bg=CARD, fg=DIM, font=("Segoe UI", 9)).pack(anchor="w", padx=12, pady=(8, 0))
        r = tk.Frame(f, bg=CARD)
        r.pack(fill="x", padx=12, pady=(0, 8))
        lab = tk.Label(r, textvariable=var, bg=CARD, fg=TEXT, anchor="w", justify="left", wraplength=int(330 * self.sf),
                       font=("Consolas", 12, "bold") if mono else ("Segoe UI", 10, "bold"))
        lab.pack(side="left", fill="x", expand=True)
        self.labels[key] = lab
        for text, cmd in (button or []):
            tk.Button(r, text=text, command=cmd, bg=BORDER, fg=TEXT, relief="flat", activebackground=ACCENT,
                      activeforeground="white", padx=10).pack(side="right", padx=(6, 0))

    def build_ui(self):
        sf = self.sf
        # top: logo, title, status (always visible)
        top = tk.Frame(self.root, bg=BG)
        top.pack(side="top", fill="x", padx=18, pady=(14, 4))
        buf = io.BytesIO()
        make_image(int(44 * sf)).save(buf, "PNG")
        self.logo_img = tk.PhotoImage(data=base64.b64encode(buf.getvalue()))
        tk.Label(top, image=self.logo_img, bg=BG).pack(side="left", padx=(0, 12))
        col = tk.Frame(top, bg=BG)
        col.pack(side="left", fill="x")
        tk.Label(col, text="Remoty Agent", bg=BG, fg=TEXT, font=("Segoe UI", 18, "bold")).pack(anchor="w")
        self.status_label = tk.Label(col, textvariable=self.v["status"], bg=BG, fg=AMBER, anchor="w", justify="left",
                                     wraplength=int(400 * sf), font=("Segoe UI", 10, "bold"))
        self.status_label.pack(anchor="w")

        # bottom: options and buttons (always visible, packed before the scrolling middle so they keep their space)
        foot = tk.Frame(self.root, bg=BG)
        foot.pack(side="bottom", fill="x", padx=18, pady=(4, 12))
        self.auto = tk.BooleanVar(value=autostart_enabled())
        tk.Checkbutton(foot, text="Start with Windows (runs hidden in the tray)", variable=self.auto, command=self.toggle_autostart,
                       bg=BG, fg=TEXT, selectcolor=CARD, activebackground=BG, activeforeground=TEXT, font=("Segoe UI", 10)).pack(anchor="w")
        self.alld = tk.BooleanVar(value=bool(ra.CFG.get("all_drives")))
        tk.Checkbutton(foot, text="Let the phone use ALL drives (C:, D:, ...)", variable=self.alld, command=self.toggle_all_drives,
                       bg=BG, fg=AMBER, selectcolor=CARD, activebackground=BG, activeforeground=AMBER, font=("Segoe UI", 10)).pack(anchor="w", pady=(0, 6))
        self.remote = tk.BooleanVar(value=bool(ra.CFG.get("remote_control")))
        tk.Checkbutton(foot, text="Allow remote control (phone shows this screen and can click and type)", variable=self.remote,
                       command=self.toggle_remote, bg=BG, fg=AMBER, selectcolor=CARD, activebackground=BG, activeforeground=AMBER,
                       font=("Segoe UI", 10)).pack(anchor="w", pady=(0, 6))
        b = tk.Frame(foot, bg=BG)
        b.pack(fill="x")
        tk.Button(b, text="Run in background", command=self.hide, bg="#E53935", fg="white", relief="flat", font=("Segoe UI", 11, "bold"),
                  activebackground="#B71C1C", activeforeground="white", padx=14, pady=8).pack(side="left", expand=True, fill="x")
        tk.Button(b, text="Activity log", command=self.open_audit, bg=BORDER, fg=TEXT, relief="flat", padx=12, pady=8).pack(side="left", padx=(6, 0))
        tk.Button(b, text="Restart", command=self.restart_server, bg=BORDER, fg=TEXT, relief="flat", padx=12, pady=8).pack(side="left", padx=6)
        tk.Button(b, text="Quit", command=self.quit, bg=BORDER, fg=RED, relief="flat", padx=12, pady=8).pack(side="left")

        # middle: the information cards, scrollable (mouse wheel or the bar on the right)
        mid = tk.Frame(self.root, bg=BG)
        mid.pack(side="top", fill="both", expand=True, padx=(18, 6))
        canvas = tk.Canvas(mid, bg=BG, highlightthickness=0)
        bar = tk.Scrollbar(mid, orient="vertical", command=canvas.yview)
        inner = tk.Frame(canvas, bg=BG)
        win = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win, width=e.width))
        canvas.configure(yscrollcommand=bar.set)
        canvas.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")
        self.root.bind_all("<MouseWheel>", lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))

        self.row(inner, "ZeroTier address of this laptop (type it into the phone app)", self.v["zt"], "zt")
        self.row(inner, "Pairing token (type it into the phone app)", self.v["token"], "token",
                 [("Copy", self.copy_token), ("New", self.new_token)], mono=True)
        self.row(inner, "Tools your scripts use", self.v["git"], "git")
        self.row(inner, "Windows Firewall", self.v["fw"], "fw", [("Allow (admin)", self.fix_firewall)])
        self.row(inner, "Folders the phone may use", self.v["folder"], "folder", [("Open", self.open_folder)])
        self.row(inner, "Jobs running", self.v["jobs"], "jobs")

    def set(self, key, text, color=TEXT):
        self.v[key].set(text)
        if key in self.labels:
            self.labels[key].configure(fg=color)

    def refresh(self):
        s = self.server
        if s is not None and getattr(s, "started", False):
            self.status_label.configure(fg=GREEN)
            self.v["status"].set(f"● Running, listening on {ra.BIND}:{ra.CFG['port']}")
        elif self.server_error or (self.thread is not None and not self.thread.is_alive()):
            self.status_label.configure(fg=RED)
            self.v["status"].set("● Not running: the port may be in use. Click Restart. " + (self.server_error or ""))
        else:
            self.status_label.configure(fg=AMBER)
            self.v["status"].set("● Starting...")
        if ra.ZT_IP:
            self.set("zt", ra.ZT_IP, GREEN)
        else:
            self.set("zt", "Not detected (the agent still works on every network card). Find the address in ZeroTier Central or `ipconfig`, put it in config.json as \"bind\", then Restart.", RED)
        self.set("token", ra.CFG["token"])
        git, gh = ra.tool("git"), ra.tool("gh")
        self.set("git", ("git ✓" if git else "git ✗") + "    " + ("GitHub CLI ✓" if gh else "GitHub CLI ✗ (your START-HERE setup installs it)"),
                 GREEN if git and gh else AMBER)
        self.set("folder", "\n".join(ra.root_list()))
        running = sum(1 for j in list(ra.JOBS.values()) if j.active())
        self.set("jobs", str(running) + ("   (keeping the laptop awake)" if ra.keeping_awake() else ""))
        self.root.after(2000, self.refresh)

    # ---- actions (all run on the Tk thread)
    def pump(self):
        try:
            while True:
                self.q.get_nowait()()
        except queue.Empty:
            pass
        self.root.after(150, self.pump)

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def hide(self):
        self.root.withdraw()
        try:
            self.tray.notify("Still running in the background. Right-click this icon to open or quit.", "Remoty Agent")
        except Exception:
            pass

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.root.update()

    def copy_token(self):
        self.copy(ra.CFG["token"])

    def new_token(self):
        ra.CFG["token"] = ra.new_token()
        ra.CONFIG_PATH.write_text(json.dumps(ra.CFG, indent=2), encoding="utf-8")
        self.set("token", ra.CFG["token"])

    def open_folder(self):
        try:
            os.startfile(ra.CFG["roots"][0])
        except Exception:
            pass

    def toggle_remote(self):
        want = self.remote.get()
        if want and not messagebox.askyesno(
                "Remote control", "With this on, the phone can see this screen and move the mouse and type, as you.\n\n"
                "It needs your pairing token. It cannot work while Windows is locked.\n\nTurn it on?"):
            self.remote.set(False)
            return
        ra.CFG["remote_control"] = want
        try:
            ra.CONFIG_PATH.write_text(json.dumps(ra.CFG, indent=2), encoding="utf-8")
        except OSError:
            pass

    def open_audit(self):
        try:
            ra.AUDIT_FILE.parent.mkdir(parents=True, exist_ok=True)
            ra.AUDIT_FILE.touch()
            os.startfile(str(ra.AUDIT_FILE))
        except Exception:
            pass

    def toggle_all_drives(self):
        want = self.alld.get()
        if want and not messagebox.askyesno(
                "Full access", "With this on, anyone who has your pairing token and is on your ZeroTier network can read, change or "
                "delete ANY file on this PC and run any script in it.\n\nKeep the token private. Turn it on?"):
            self.alld.set(False)
            return
        ra.CFG["all_drives"] = want
        try:
            ra.CONFIG_PATH.write_text(json.dumps(ra.CFG, indent=2), encoding="utf-8")
        except OSError:
            pass

    def toggle_autostart(self):
        want = not autostart_enabled()
        try:
            set_autostart(want)
        except Exception:
            pass
        self.auto.set(autostart_enabled())

    def fix_firewall(self):
        add_firewall()
        self.root.after(5000, self.check_firewall)

    def check_firewall(self):
        ok = firewall_ok()
        self.set("fw", "Allowed ✓" if ok else "Not set. Click Allow and accept the Windows prompt.", GREEN if ok else AMBER)

    def first_run_firewall(self):
        ok = firewall_ok()
        self.q.put(lambda: self.set("fw", "Allowed ✓" if ok else "Not set. Click Allow and accept the Windows prompt.", GREEN if ok else AMBER))
        if not ok and not ra.CFG.get("firewall_prompted"):
            ra.CFG["firewall_prompted"] = True
            try:
                ra.CONFIG_PATH.write_text(json.dumps(ra.CFG, indent=2), encoding="utf-8")
            except OSError:
                pass
            self.q.put(self.fix_firewall)

    # ---- server
    def start_server(self):
        ra.load_jobs_once()
        self.server_error = None
        self.server = ra.make_server()
        srv = self.server

        def run():
            try:
                srv.run()
            except BaseException as e:  # SystemExit when the port is taken
                self.server_error = repr(e)

        self.thread = threading.Thread(target=run, daemon=True)
        self.thread.start()

    def stop_server(self):
        if self.server is not None:
            self.server.should_exit = True
        if self.thread is not None:
            self.thread.join(timeout=5)

    def restart_server(self):
        self.stop_server()
        ra.BIND = ra.resolve_bind()   # picks up a ZeroTier address that appeared since the start
        self.start_server()

    def quit(self):
        for j in list(ra.JOBS.values()):
            if j.active():
                j.stop_flag.set()
                for p in list(j.procs):
                    ra.kill_tree(p)
        self.stop_server()
        try:
            self.tray.stop()
        except Exception:
            pass
        self.root.destroy()
        os._exit(0)


if __name__ == "__main__":
    App().root.mainloop()
