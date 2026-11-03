"""
Remoty Agent - runs on your Windows 11 laptop and does the work for the Remoty phone app.

The phone sends a project zip. The agent unzips it over your project folder (like you do by hand), runs the project's own
script (for example Rebuild-APK.bat) with a live console you can type into, and hands the finished APK back.

 * Listens ONLY on the ZeroTier address, protected by a pairing token.
 * Only touches folders under "roots" in config.json. Zip entries that try to escape the folder are rejected.
 * Only runs a script that is inside the project folder (.bat / .cmd / .ps1). There is NO "run any command" endpoint.
 * The phone polls /jobs/<id>?since=N, which survives flaky mobile connections.
"""
import argparse, codecs, glob, hmac, io, json, os, re, secrets, shutil, signal, socket, subprocess
import platform, sys, threading, time, uuid, zipfile
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, Response
from pydantic import BaseModel

VERSION = "2.0.0"
HERE = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
CONFIG_PATH = Path(os.environ.get("REMOTY_CONFIG", HERE / "config.json"))
DATA_DIR = Path(os.environ.get("REMOTY_DATA", HERE / "data"))
LOG_DIR = DATA_DIR / "logs"
UPLOAD_DIR = DATA_DIR / "uploads"
BACKUP_DIR = DATA_DIR / "backups"
JOBS_FILE = DATA_DIR / "jobs.json"
AUDIT_FILE = DATA_DIR / "audit.log"
STARTED_AT = time.time()
IS_WIN = os.name == "nt"
if IS_WIN:
    try:
        import ctypes as _ct
        _ct.windll.shcore.SetProcessDpiAwareness(1)   # screen and mouse coordinates must be real pixels
    except Exception:
        pass
NOWIN = subprocess.CREATE_NO_WINDOW if IS_WIN else 0

SKIP_DIRS = {".git", "node_modules", "__pycache__", ".gradle", ".venv", ".idea", "build"}
SCRIPT_EXT = (".bat", ".cmd", ".ps1") if IS_WIN else (".bat", ".cmd", ".ps1", ".sh")
TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I
ERROR_FILE = "build-error.txt"   # your Setup script writes this when a build fails


# ----------------------------------------------------------------------------- config
def new_token() -> str:
    raw = "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(12))
    return "-".join(raw[i:i + 4] for i in range(0, 12, 4))


def load_config() -> dict:
    cfg = {}
    if CONFIG_PATH.exists():
        cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))
    changed = False
    defaults = {
        "name": socket.gethostname(),
        "bind": "auto",             # "auto" = the ZeroTier address of this laptop; "all" = every network card
        "port": 8765,
        "token": None,
        "roots": [str(Path.home() / "RemotyApps")],
        "firewall_prompted": False,
        "remote_control": False,    # True = the phone may see the screen AND move the mouse / type (Remote screen)
        "keep_awake": True,         # keep Windows from sleeping while a job runs
        "all_drives": False,        # True = the phone may browse and use every drive (C:, D:, ...). Off by default.
        "max_upload_mb": 1024,
        "env": {},                  # extra environment variables for your scripts
    }
    for k, v in defaults.items():
        if k not in cfg or cfg[k] is None:
            cfg[k] = new_token() if k == "token" else v
            changed = True
    for old in ("claude_path", "claude_permission_mode", "claude_allowed_tools", "claude_extra_args", "allow_remoty_json"):
        if old in cfg:           # Claude Code is no longer used
            del cfg[old]
            changed = True
    if changed:
        CONFIG_PATH.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


CFG = load_config()
for _r in CFG["roots"]:
    Path(_r).mkdir(parents=True, exist_ok=True)
for _d in (LOG_DIR, UPLOAD_DIR, BACKUP_DIR):
    _d.mkdir(parents=True, exist_ok=True)


def parse_zt_ip(text: str):
    """Finds the IPv4 of the ZeroTier adapter in `ipconfig /all` output.
    Newer ZeroTier versions name the adapter after your network, so we also look at the driver description
    ("ZeroTier Virtual Port"), not only at the adapter name."""
    blocks, cur = [], None
    for line in text.splitlines():
        if not line.strip():
            continue
        if not line[0].isspace() and line.rstrip().endswith(":"):
            cur = [line]
            blocks.append(cur)
        elif cur is not None:
            cur.append(line)
    for blk in blocks:
        if "zerotier" not in "\n".join(blk).lower():
            continue
        for line in blk:
            if "IPv4" in line:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", line)
                if m and not m.group(1).startswith("169.254."):
                    return m.group(1)
    return None


def detect_zt_ip():
    """IPv4 address of the ZeroTier adapter on this Windows laptop (None if there is none)."""
    if not IS_WIN:
        return None
    try:
        out = subprocess.run(["ipconfig", "/all"], capture_output=True, text=True, errors="replace", timeout=20,
                             creationflags=NOWIN).stdout
        ip = parse_zt_ip(out)
        if ip:
            return ip
    except Exception:
        pass
    try:  # second way: ask Windows for adapters whose driver is ZeroTier
        ps = ("Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*ZeroTier*' } | "
              "ForEach-Object { Get-NetIPAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue } | "
              "ForEach-Object { $_.IPAddress }")
        out = subprocess.run(["powershell.exe", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                             errors="replace", timeout=30, creationflags=NOWIN).stdout
        for line in out.splitlines():
            line = line.strip()
            if re.fullmatch(r"\d+\.\d+\.\d+\.\d+", line) and not line.startswith("169.254."):
                return line
    except Exception:
        pass
    return None


ZT_IP = None


def resolve_bind() -> str:
    """Where to listen. 'auto' (and the old default 0.0.0.0) means: only on the ZeroTier address."""
    global ZT_IP
    ZT_IP = detect_zt_ip()
    want = str(CFG.get("bind", "auto"))
    if want in ("auto", "0.0.0.0"):
        return ZT_IP or "0.0.0.0"
    if want == "all":
        return "0.0.0.0"
    return want


BIND = resolve_bind()


def tool(name: str) -> Optional[str]:
    return shutil.which(name)


def oem_encoding() -> str:
    """Console programs (cmd, PowerShell) write in the OEM code page."""
    enc = "utf-8"
    if IS_WIN:
        try:
            import ctypes
            enc = f"cp{ctypes.windll.kernel32.GetOEMCP()}"
        except Exception:
            pass
    try:
        codecs.lookup(enc)
    except LookupError:
        enc = "utf-8"
    return enc


ENC = oem_encoding()


# ----------------------------------------------------------------------------- security
def _norm(p: str) -> str:
    return os.path.normcase(os.path.realpath(p))


def drive_roots() -> list:
    """Every fixed or removable drive (C:\\, D:\\, ...)."""
    if not IS_WIN:
        return ["/"]
    try:
        import ctypes
        mask = ctypes.windll.kernel32.GetLogicalDrives()
        out = []
        for i in range(26):
            if mask & (1 << i):
                d = f"{chr(65 + i)}:\\"
                if ctypes.windll.kernel32.GetDriveTypeW(d) in (2, 3):   # removable, fixed
                    out.append(d)
        return out
    except Exception:
        return []


def root_list() -> list:
    """The folders the phone may use: the configured ones, plus every drive when all_drives is on."""
    base = list(CFG["roots"])
    if CFG.get("all_drives"):
        base += [d for d in drive_roots() if d not in base]
    return base


def roots() -> list:
    return [_norm(r) for r in root_list()]


def safe_path(p: str) -> str:
    """Resolve p and make sure it is inside one of the allowed roots (blocks .. and symlink escapes)."""
    if not p:
        raise HTTPException(400, "path required")
    real = _norm(p)
    for r in roots():
        try:
            if os.path.commonpath([r, real]) == r:
                return real if not IS_WIN else os.path.realpath(p)
        except ValueError:  # different drives on Windows
            continue
    raise HTTPException(403, "path is outside the allowed folders")


_fail = {}  # ip -> (count, until)


def auth(request: Request, x_remoty_token: Optional[str] = Header(default=None)):
    ip = request.client.host if request.client else "?"
    count, until = _fail.get(ip, (0, 0))
    if until > time.time():
        raise HTTPException(429, "too many wrong tokens, wait a minute")
    if not x_remoty_token or not hmac.compare_digest(x_remoty_token.strip().upper(), CFG["token"].upper()):
        audit(request, "DENIED", "wrong or missing token", every=10)
        count += 1
        _fail[ip] = (count, time.time() + 60 if count >= 5 else 0)
        raise HTTPException(401, "wrong token")
    _fail.pop(ip, None)


# ----------------------------------------------------------------------------- jobs
class Job:
    def __init__(self, folder: str, script: str, upload_id: Optional[str], title: str, zip_name: str = ""):
        self.id = uuid.uuid4().hex[:10]
        self.folder = folder
        self.script = script
        self.upload_id = upload_id
        self.zip_name = zip_name
        self.title = title
        self.status = "queued"   # queued preparing running succeeded failed stopped
        self.phase = ""
        self.created = time.time()
        self.finished = None
        self.exit_code = None
        self.backup = None       # zip with the files this job replaced (for rollback)
        self.artifact = None     # the APK the script produced
        self.preview_port = None
        self.waiting_input = False
        self.lines = []          # [{k, s}]
        self.main_proc = None
        self.procs = []
        self.relays = []
        self.stop_flag = threading.Event()
        self.lock = threading.Lock()
        self.started_at = time.time()

    def log(self, kind: str, text: str):
        text = text.rstrip("\r\n")
        with self.lock:
            self.lines.append({"k": kind, "s": text})
        try:
            with open(LOG_DIR / f"{self.id}.log", "a", encoding="utf-8", errors="replace") as f:
                f.write(f"[{kind}] {text}\n")
        except OSError:
            pass

    def send_input(self, text: str):
        p = self.main_proc
        if p is None or p.poll() is not None or p.stdin is None:
            raise HTTPException(409, "the script is not running")
        data = (text + ("\r\n" if IS_WIN else "\n")).encode(ENC, errors="replace")
        try:
            p.stdin.write(data)
            p.stdin.flush()
        except OSError:
            raise HTTPException(409, "the script no longer accepts input")
        self.waiting_input = False
        self.log("input", "> " + text)

    def summary(self) -> dict:
        return {
            "id": self.id, "title": self.title, "folder": self.folder, "script": self.script, "zip_name": self.zip_name,
            "status": self.status, "phase": self.phase, "created": self.created, "finished": self.finished,
            "exit_code": self.exit_code, "has_artifact": bool(self.artifact),
            "artifact_name": os.path.basename(self.artifact) if self.artifact else None,
            "preview_port": self.preview_port, "can_rollback": bool(self.backup and os.path.exists(self.backup)),
            "waiting_input": self.waiting_input and self.active(),
            "last_line": self.last_line(),
        }

    def last_line(self) -> str:
        with self.lock:
            for ln in reversed(self.lines[-40:]):
                if ln["k"] in ("out", "err", "sys", "prompt") and ln["s"].strip():
                    return ln["s"].strip()[:140]
        return ""

    def active(self) -> bool:
        return self.status in ("queued", "preparing", "running")


JOBS = {}
JOBS_LOCK = threading.Lock()


def save_jobs():
    with JOBS_LOCK:
        items = sorted(JOBS.values(), key=lambda j: j.created)[-100:]
        data = [j.summary() | {"backup": j.backup, "artifact": j.artifact} for j in items]
    try:
        JOBS_FILE.write_text(json.dumps(data), encoding="utf-8")
    except OSError:
        pass


def load_jobs():
    if not JOBS_FILE.exists():
        return
    try:
        for d in json.loads(JOBS_FILE.read_text(encoding="utf-8-sig")):
            j = Job(d["folder"], d.get("script", ""), None, d["title"], d.get("zip_name", ""))
            j.id, j.created, j.finished = d["id"], d["created"], d.get("finished")
            j.status = d["status"] if d["status"] not in ("queued", "preparing", "running") else "stopped"
            j.phase, j.exit_code = d.get("phase", ""), d.get("exit_code")
            j.backup, j.artifact = d.get("backup"), d.get("artifact")
            logf = LOG_DIR / f"{j.id}.log"
            if logf.exists():
                for ln in logf.read_text(encoding="utf-8", errors="replace").splitlines()[-3000:]:
                    m = re.match(r"\[(\w+)\] (.*)", ln)
                    j.lines.append({"k": m.group(1), "s": m.group(2)} if m else {"k": "out", "s": ln})
            JOBS[j.id] = j
    except Exception:
        pass


_jobs_loaded = False


def load_jobs_once():
    global _jobs_loaded
    if not _jobs_loaded:
        _jobs_loaded = True
        load_jobs()
        cutoff = time.time() - 3 * 86400    # forget uploaded zips after 3 days
        for f in UPLOAD_DIR.glob("*.zip"):
            try:
                if f.stat().st_mtime < cutoff:
                    f.unlink()
            except OSError:
                pass


# ----------------------------------------------------------------------------- process output (live console)
ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
AUTO_ENTER_RE = re.compile(r"press (any key|enter)|enter to (continue|close)|any key to continue", re.I)
URL_RE = re.compile(r"https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d+)")


class Pump:
    """Reads a process pipe. Complete lines are logged at once; a half line that stays idle is a prompt waiting for you."""

    def __init__(self, job: Job, stream, kind: str):
        self.job, self.stream, self.kind = job, stream, kind
        self.pending = ""
        self.last = time.time()
        self.lock = threading.Lock()
        self.dec = codecs.getincrementaldecoder(ENC)(errors="replace")
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        try:
            fd = self.stream.fileno()
            while True:
                data = os.read(fd, 4096)
                if not data:
                    break
                self._feed(self.dec.decode(data))
        except (OSError, ValueError):
            pass
        with self.lock:
            rest, self.pending = self.pending, ""
        if rest.strip():
            self._emit(rest, False)

    def _feed(self, text: str):
        text = ANSI_RE.sub("", text).replace("\r\n", "\n")
        with self.lock:
            self.pending += text
            *lines, self.pending = self.pending.split("\n")
            self.last = time.time()
        for ln in lines:
            self._emit(ln, False)

    def _emit(self, line: str, prompt: bool):
        line = line.split("\r")[-1]          # progress bars redraw the same line: keep the last version
        job = self.job
        if prompt:
            job.log("prompt", line)
            job.waiting_input = True
            if AUTO_ENTER_RE.search(line):   # "Press any key to continue": just continue
                try:
                    job.send_input("")
                except HTTPException:
                    pass
            return
        job.log(self.kind, line)
        m = URL_RE.search(line)
        if m and not job.preview_port:
            start_relay(job, int(m.group(1)))

    def tick(self):
        with self.lock:
            p, idle = self.pending, time.time() - self.last
            if not p.strip() or idle < 0.6:
                return
            self.pending = ""
        self._emit(p, True)


def kill_tree(p: subprocess.Popen):
    if p.poll() is not None:
        return
    try:
        if IS_WIN:
            subprocess.run(["taskkill", "/PID", str(p.pid), "/T", "/F"], capture_output=True, creationflags=NOWIN)
        else:
            os.killpg(os.getpgid(p.pid), signal.SIGTERM)
    except Exception:
        try:
            p.kill()
        except Exception:
            pass


def proc_env() -> dict:
    env = os.environ.copy()
    env.update({k: str(v) for k, v in CFG.get("env", {}).items()})
    env["PYTHONUNBUFFERED"] = "1"
    return env


# ----------------------------------------------------------------------------- preview relay (web address printed by a script)
class Relay:
    """Makes a localhost-only server reachable over the ZeroTier address (for the phone's preview)."""

    def __init__(self, target_port: int):
        self.target = target_port
        self.srv = socket.socket()
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind((BIND, 0))
        self.srv.listen(20)
        self.port = self.srv.getsockname()[1]
        self.alive = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while self.alive:
            try:
                c, _ = self.srv.accept()
            except OSError:
                return
            try:
                u = socket.create_connection(("127.0.0.1", self.target), timeout=5)
            except OSError:
                c.close()
                continue
            for a, b in ((c, u), (u, c)):
                threading.Thread(target=self._copy, args=(a, b), daemon=True).start()

    @staticmethod
    def _copy(a, b):
        try:
            while True:
                d = a.recv(65536)
                if not d:
                    break
                b.sendall(d)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.close()
                except OSError:
                    pass

    def close(self):
        self.alive = False
        try:
            self.srv.close()
        except OSError:
            pass


def start_relay(job: Job, port: int):
    if job.preview_port:
        return
    try:
        r = Relay(port)
        job.relays.append(r)
        job.preview_port = r.port
        job.log("sys", f"preview ready (web address on port {port})")
    except OSError as e:
        job.log("sys", f"preview relay failed: {e}")


# ----------------------------------------------------------------------------- zip handling
def _bad_member(rel: str) -> bool:
    parts = rel.replace("\\", "/").split("/")
    return rel.startswith(("/", "\\")) or ":" in rel or ".." in parts


def zip_plan(zpath: str, folder: str):
    """Returns [(ZipInfo, rel, dest)] for the files that would be written. Strips one top-level folder, like unzipping over the project."""
    with zipfile.ZipFile(zpath) as z:
        infos = [i for i in z.infolist() if not i.filename.startswith("__MACOSX/") and i.filename.strip("/")]
    prefix = ""
    tops = {i.filename.split("/")[0] for i in infos}
    if len(tops) == 1:
        t = next(iter(tops))
        if all(i.filename.startswith(t + "/") for i in infos):
            prefix = t + "/"
    plan = []
    base = os.path.realpath(folder)
    for i in infos:
        if i.is_dir():
            continue
        rel = i.filename[len(prefix):] if prefix else i.filename
        if not rel:
            continue
        if _bad_member(rel):
            raise HTTPException(400, f"the zip contains an unsafe path: {rel}")
        if rel.replace("\\", "/").split("/")[0] == ".git":
            continue                                   # never touch the project's git history
        dest = os.path.realpath(os.path.join(folder, rel))
        if os.path.commonpath([base, dest]) != base:
            raise HTTPException(400, f"the zip contains an unsafe path: {rel}")
        plan.append((i, rel.replace("\\", "/"), dest))
    return plan


def list_scripts(folder: str) -> list:
    out = []
    base = os.path.realpath(folder)
    if not os.path.isdir(base):
        return out
    for dp, dn, fn in os.walk(base):
        depth = os.path.relpath(dp, base).count(os.sep) if dp != base else 0
        dn[:] = [d for d in dn if d not in SKIP_DIRS and depth < 1]
        for f in fn:
            if f.lower().endswith(SCRIPT_EXT):
                out.append(os.path.relpath(os.path.join(dp, f), base).replace("\\", "/"))
    return sorted(out, key=lambda s: (s.count("/"), s.lower()))


def pick_default(scripts: list) -> Optional[str]:
    low = {s.lower(): s for s in scripts}
    for want in ("rebuild-apk.bat", "start-here.bat"):
        if want in low:
            return low[want]
    roots_ = [s for s in scripts if "/" not in s and s.lower().endswith((".bat", ".cmd"))]
    return roots_[0] if roots_ else (scripts[0] if scripts else None)


def upload_path(upload_id: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{10}", upload_id or ""):
        raise HTTPException(400, "bad upload id")
    p = UPLOAD_DIR / f"{upload_id}.zip"
    if not p.exists():
        raise HTTPException(404, "that upload is gone, send the zip again")
    return str(p)


def unzip_over(job: Job, zpath: str, folder: str):
    plan = zip_plan(zpath, folder)
    backup = BACKUP_DIR / f"{job.id}.zip"
    new_files, replaced = [], 0
    with zipfile.ZipFile(backup, "w", zipfile.ZIP_DEFLATED) as bz:
        for _, rel, dest in plan:
            if os.path.isfile(dest):
                bz.write(dest, arcname=rel)
                replaced += 1
            else:
                new_files.append(rel)
        bz.writestr("__remoty_new_files.json", json.dumps(new_files))
    job.backup = str(backup)
    with zipfile.ZipFile(zpath) as z:
        for info, rel, dest in plan:
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(info) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
    job.log("sys", f"unzipped {len(plan)} files into {folder} ({replaced} replaced, {len(new_files)} new)")


def rollback(job: Job):
    if not job.backup or not os.path.exists(job.backup):
        raise HTTPException(400, "nothing to roll back for this job")
    base = os.path.realpath(job.folder)
    with zipfile.ZipFile(job.backup) as bz:
        new_files = json.loads(bz.read("__remoty_new_files.json"))
        for name in bz.namelist():
            if name == "__remoty_new_files.json":
                continue
            dest = os.path.realpath(os.path.join(base, name))
            if os.path.commonpath([base, dest]) != base:
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with bz.open(name) as src, open(dest, "wb") as out:
                shutil.copyfileobj(src, out)
    for rel in new_files:
        dest = os.path.realpath(os.path.join(base, rel))
        if os.path.commonpath([base, dest]) == base and os.path.isfile(dest):
            os.remove(dest)
    job.log("sys", "rolled back: replaced files restored, new files removed")
    os.remove(job.backup)
    job.backup = None


# ----------------------------------------------------------------------------- running the script
def script_command(folder: str, rel: str) -> list:
    low = rel.lower()
    if low.endswith(".ps1"):
        return ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", rel]
    if low.endswith(".sh"):
        return ["bash", rel]
    return ["cmd.exe", "/d", "/c", rel.replace("/", "\\")]


def find_apks(folder: str, since: float) -> Optional[str]:
    best, best_m = None, 0.0
    base = os.path.realpath(folder)
    for dp, dn, fn in os.walk(base):
        depth = os.path.relpath(dp, base).count(os.sep) if dp != base else 0
        dn[:] = [d for d in dn if d not in (".git", "node_modules", ".gradle") and depth < 4]
        for f in fn:
            if f.lower().endswith(".apk"):
                full = os.path.join(dp, f)
                m = os.path.getmtime(full)
                if m >= since - 2 and m > best_m:
                    best, best_m = full, m
    return best


def run_script(job: Job) -> bool:
    folder, rel = job.folder, job.script
    cmd = script_command(folder, rel)
    job.status, job.phase = "running", f"Running {rel}"
    job.started_at = time.time()
    job.log("sys", "$ " + " ".join(cmd))
    kw = dict(cwd=folder, env=proc_env(), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    if IS_WIN:
        kw["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    else:
        kw["start_new_session"] = True
    try:
        p = subprocess.Popen(cmd, **kw)
    except OSError as e:
        job.log("err", f"could not start {cmd[0]}: {e}")
        return False
    job.main_proc = p
    job.procs.append(p)
    pumps = [Pump(job, p.stdout, "out"), Pump(job, p.stderr, "err")]
    while p.poll() is None:
        if job.stop_flag.is_set():
            kill_tree(p)
            break
        for pu in pumps:
            pu.tick()
        time.sleep(0.15)
    for pu in pumps:
        pu.thread.join(timeout=3)
    job.exit_code = p.returncode
    job.waiting_input = False
    ok = p.returncode == 0
    err_file = Path(folder) / ERROR_FILE
    try:
        if err_file.exists() and err_file.stat().st_mtime >= job.started_at - 2:
            ok = False
            job.log("err", f"--- {ERROR_FILE} ---")
            for ln in err_file.read_text(encoding="utf-8-sig", errors="replace").splitlines()[-60:]:
                job.log("err", ln)
    except OSError:
        pass
    apk = find_apks(folder, job.started_at)
    if apk:
        job.artifact = apk
        job.log("sys", f"APK found: {os.path.basename(apk)}")
    if p.returncode != 0:
        job.log("err", f"the script ended with exit code {p.returncode}")
    return ok


def job_worker(job: Job):
    try:
        ok = True
        if job.upload_id:
            job.status, job.phase = "preparing", "Unzipping"
            unzip_over(job, upload_path(job.upload_id), job.folder)
        if job.script and not job.stop_flag.is_set():
            ok = run_script(job)
        elif not job.script:
            job.log("sys", "no script chosen: only unzipped")
        job.status = "stopped" if job.stop_flag.is_set() else ("succeeded" if ok else "failed")
        job.phase = {"succeeded": "Done", "failed": "Failed", "stopped": "Stopped"}[job.status]
    except HTTPException as e:
        job.log("err", str(e.detail))
        job.status, job.phase = "failed", "Failed"
    except Exception as e:  # never leave a job "running" after a crash
        job.log("err", f"agent error: {type(e).__name__}: {e}")
        job.status, job.phase = "failed", "Agent error"
    finally:
        for r in job.relays:
            r.close()
        job.finished = time.time()
        save_jobs()


# ----------------------------------------------------------------------------- activity log (everything the phone did)
_audit_lock = threading.Lock()
_audit_last = {}


def client_ip(request) -> str:
    try:
        return request.client.host if request is not None and request.client else "?"
    except Exception:
        return "?"


def audit(request, action: str, detail: str = "", every: float = 0.0):
    """Appends one line to data/audit.log. `every` throttles noisy actions (e.g. browsing, screen views) to one per N seconds."""
    if every:
        key = (client_ip(request), action)
        now = time.time()
        if now - _audit_last.get(key, 0) < every:
            return
        _audit_last[key] = now
    rec = {"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "ip": client_ip(request), "action": action, "detail": str(detail)[:300]}
    with _audit_lock:
        try:
            if AUDIT_FILE.exists() and AUDIT_FILE.stat().st_size > 5 * 1024 * 1024:
                AUDIT_FILE.replace(AUDIT_FILE.with_name("audit.1.log"))
            with open(AUDIT_FILE, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except OSError:
            pass


def audit_tail(limit: int = 200) -> list:
    try:
        lines = AUDIT_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-limit:]
    except OSError:
        return []
    out = []
    for ln in reversed(lines):          # newest first
        try:
            out.append(json.loads(ln))
        except ValueError:
            pass
    return out


# ----------------------------------------------------------------------------- smart error analysis
KT_ERR = re.compile(r"^e: (?:file://)?(?P<file>.+?\.kts?):(?P<line>\d+):(?P<col>\d+):?\s*(?P<msg>.*)$")
FILE_LINE = re.compile(r"(?P<file>[\w./\\:-]+\.(?:ps1|bat|py|js|ts|kts|kt|gradle|java|xml|yml|yaml)):(?P<line>\d+)")
TITLE_PATTERNS = re.compile(r"^(?:> )?(Execution failed for task .*|Could not (?:resolve|find|get|download) .*|Unresolved reference.*|"
                            r"Task .* FAILED|BUILD FAILED.*|\[X\] .*|The term .* is not recognized.*|.*is missing\..*|.*was not found.*)$")


def parse_problems(lines: list) -> list:
    """Pulls the real errors out of a long build log: Kotlin compiler errors with file and line, Gradle 'What went wrong',
    CI ##[error] lines, Python tracebacks and the script's own [X] messages."""
    raws = [(l["s"] if isinstance(l, dict) else str(l)) for l in lines]
    texts = [r.strip() for r in raws]
    out, seen = [], set()

    def add(title, detail="", file="", line=0):
        title = title.strip()
        key = (title, file, line)
        if not title or key in seen or len(out) >= 12:
            return
        seen.add(key)
        out.append({"title": title[:220], "detail": detail.strip()[:500], "file": file, "line": line})

    n = len(texts)
    for i, t in enumerate(texts):
        m = KT_ERR.match(t)
        if m:
            add(m.group("msg") or "Kotlin compile error", "", os.path.basename(m.group("file")), int(m.group("line")))
            continue
        if t.startswith("* What went wrong:") and i + 1 < n:
            nxt = [x for x in texts[i + 1:i + 5] if x and not x.startswith("*")]
            if nxt:
                add(nxt[0], " ".join(nxt[1:3]))
            continue
        if t.startswith("Traceback (most recent call last)"):
            last_file, last_line = "", 0
            for j in range(i + 1, min(i + 60, n)):
                mm = re.match(r'File "(.+?)", line (\d+)', texts[j])
                if mm:
                    last_file, last_line = os.path.basename(mm.group(1)), int(mm.group(2))
                elif texts[j] and not raws[j].startswith((" ", "\t")):
                    add("Python: " + texts[j], "", last_file, last_line)
                    break
            continue
        if "##[error]" in t:
            add(t.replace("##[error]", "").strip())
            continue
        if TITLE_PATTERNS.match(t):
            add(t)
            continue
        if re.search(r"\b(error|exception)\b", t, re.I):
            mm = FILE_LINE.search(t)
            if mm:
                add(t, "", os.path.basename(mm.group("file")), int(mm.group("line")))
    # specific errors first, generic wrappers (BUILD FAILED, Execution failed) last
    out.sort(key=lambda p: (0 if p["file"] else 1 if not re.match(r"BUILD FAILED|Execution failed|Process completed|\[X\]", p["title"]) else 2))
    return out


def problems_text(problems: list) -> str:
    if not problems:
        return ""
    rows = []
    for i, p in enumerate(problems, 1):
        loc = f" ({p['file']}:{p['line']})" if p.get("file") else ""
        rows.append(f"{i}. {p['title']}{loc}" + (f"\n   {p['detail']}" if p.get("detail") else ""))
    return "### What went wrong\n" + "\n".join(rows) + "\n\n"


# ----------------------------------------------------------------------------- keep the laptop awake while a job runs
def _keep_awake_loop():
    if not IS_WIN:
        return
    import ctypes
    ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
    k = ctypes.windll.kernel32
    awake = False
    while True:
        want = bool(CFG.get("keep_awake", True)) and any(j.active() for j in list(JOBS.values()))
        if want:
            k.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)   # this thread keeps the request alive
            awake = True
        elif awake:
            k.SetThreadExecutionState(ES_CONTINUOUS)                        # release
            awake = False
        time.sleep(15)


def keeping_awake() -> bool:
    return bool(CFG.get("keep_awake", True)) and any(j.active() for j in list(JOBS.values()))


threading.Thread(target=_keep_awake_loop, daemon=True).start()


# ----------------------------------------------------------------------------- remote screen: mouse and keyboard
VKEYS = {"enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "delete": 0x2E, "space": 0x20, "left": 0x25, "up": 0x26,
         "right": 0x27, "down": 0x28, "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "win": 0x5B,
         "f5": 0x74, "f11": 0x7A, "printscreen": 0x2C}
VMODS = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B}


def screen_rect():
    import mss
    with mss.mss() as sct:
        m = sct.monitors[1]
        return m["left"], m["top"], m["width"], m["height"]


def _type_text(text: str):
    import ctypes
    from ctypes import wintypes

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]

    class _U(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", _U)]

    KEYEVENTF_UNICODE, KEYEVENTF_KEYUP = 0x0004, 0x0002
    for ch in text:
        for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
            inp = INPUT(type=1, u=_U(ki=KEYBDINPUT(0, ord(ch), flags, 0, 0)))
            ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def do_remote(body: dict):
    if not CFG.get("remote_control"):
        raise HTTPException(403, "Remote control is off. On the laptop, tick 'Allow remote control' in the Remoty Agent window.")
    if not IS_WIN:
        raise HTTPException(501, "remote control works on Windows only")
    import ctypes
    u = ctypes.windll.user32
    act = str(body.get("action", ""))
    left, top, w, h = screen_rect()

    def pos():
        x = min(max(float(body.get("x", 0.5)), 0.0), 1.0)
        y = min(max(float(body.get("y", 0.5)), 0.0), 1.0)
        u.SetCursorPos(int(left + x * (w - 1)), int(top + y * (h - 1)))

    DOWN, UP, RDOWN, RUP, WHEEL = 0x0002, 0x0004, 0x0008, 0x0010, 0x0800
    if act == "click":
        pos(); u.mouse_event(DOWN, 0, 0, 0, 0); u.mouse_event(UP, 0, 0, 0, 0)
    elif act == "dblclick":
        pos()
        for _ in range(2):
            u.mouse_event(DOWN, 0, 0, 0, 0); u.mouse_event(UP, 0, 0, 0, 0); time.sleep(0.05)
    elif act == "rclick":
        pos(); u.mouse_event(RDOWN, 0, 0, 0, 0); u.mouse_event(RUP, 0, 0, 0, 0)
    elif act == "down":
        pos(); u.mouse_event(DOWN, 0, 0, 0, 0)
    elif act == "move":
        pos()
    elif act == "up":
        pos(); u.mouse_event(UP, 0, 0, 0, 0)
    elif act == "scroll":
        pos(); u.mouse_event(WHEEL, 0, 0, int(-float(body.get("dy", 0)) * 120) & 0xFFFFFFFF, 0)
    elif act == "text":
        _type_text(str(body.get("text", ""))[:500])
    elif act == "key":
        key = str(body.get("key", "")).lower()
        vk = VKEYS.get(key) or (u.VkKeyScanW(ord(key[0])) & 0xFF if len(key) == 1 else 0)
        if not vk:
            raise HTTPException(400, "unknown key")
        mods = [VMODS[m] for m in body.get("mods", []) if m in VMODS]
        for m in mods:
            u.keybd_event(m, 0, 0, 0)
        u.keybd_event(vk, 0, 0, 0)
        u.keybd_event(vk, 0, 2, 0)
        for m in reversed(mods):
            u.keybd_event(m, 0, 2, 0)
    else:
        raise HTTPException(400, "unknown action")


# ----------------------------------------------------------------------------- API
app = FastAPI(title="Remoty Agent", version=VERSION)


class JobIn(BaseModel):
    folder: str
    upload_id: Optional[str] = None
    script: str = ""
    title: str = ""


class PathIn(BaseModel):
    path: str


class WriteIn(BaseModel):
    path: str
    text: str


class InputIn(BaseModel):
    text: str = ""


@app.get("/health")
def health():
    return {"ok": True, "name": CFG["name"], "version": VERSION}


@app.get("/info", dependencies=[Depends(auth)])
def info():
    return {
        "name": CFG["name"], "version": VERSION, "os": sys.platform, "roots": root_list(),
        "tools": {t: bool(tool(t)) for t in ("git", "gh", "powershell", "java")},
        "running": sum(1 for j in JOBS.values() if j.active()),
        "remote_control": bool(CFG.get("remote_control")), "keep_awake": bool(CFG.get("keep_awake", True)), "awake_now": keeping_awake(),
    }


@app.get("/fs/list", dependencies=[Depends(auth)])
def fs_list(request: Request, path: str = "", hidden: int = 0):
    audit(request, "browse", path or "(drives)", every=3)
    if not path:
        return {"path": "", "parent": None, "entries": [
            {"name": r, "path": r, "dir": True, "size": 0, "mtime": 0} for r in root_list()]}
    real = safe_path(path)
    if not os.path.isdir(real):
        raise HTTPException(404, "not a folder")
    out = []
    try:
        for e in os.scandir(real):
            if not hidden and (e.name in SKIP_DIRS - {"build"}):
                continue
            try:
                st = e.stat()
            except OSError:
                continue
            out.append({"name": e.name, "path": os.path.join(real, e.name), "dir": e.is_dir(),
                        "size": st.st_size, "mtime": st.st_mtime})
    except PermissionError:
        raise HTTPException(403, "no permission")
    out.sort(key=lambda x: (not x["dir"], x["name"].lower()))
    parent = os.path.dirname(real)
    try:
        safe_path(parent)
        if os.path.normcase(parent) == os.path.normcase(real):
            parent = ""  # a drive root is its own parent: going up returns to the root list
    except HTTPException:
        parent = ""  # at a root: going up returns to the root list
    return {"path": real, "parent": parent, "entries": out[:1000]}


@app.post("/fs/mkdir", dependencies=[Depends(auth)])
def fs_mkdir(request: Request, body: PathIn):
    audit(request, "new folder", body.path)
    name = os.path.basename(body.path.rstrip("\\/"))
    if not name or any(c in name for c in '<>:"|?*'):
        raise HTTPException(400, "bad folder name")
    real = safe_path(body.path)
    os.makedirs(real, exist_ok=True)
    return {"path": real}


@app.get("/fs/read", dependencies=[Depends(auth)])
def fs_read(request: Request, path: str):
    audit(request, "open file", path)
    real = safe_path(path)
    if not os.path.isfile(real):
        raise HTTPException(404, "not a file")
    size = os.path.getsize(real)
    if size > 400_000:
        raise HTTPException(413, "file too large to open on the phone (400 KB limit)")
    raw = Path(real).read_bytes()
    try:
        return {"path": real, "text": raw.decode("utf-8-sig"), "size": size}
    except UnicodeDecodeError:
        raise HTTPException(415, "not a text file")


@app.put("/fs/write", dependencies=[Depends(auth)])
def fs_write(request: Request, body: WriteIn):
    audit(request, "save file", f"{body.path} ({len(body.text)} chars)")
    real = safe_path(body.path)
    if len(body.text) > 400_000:
        raise HTTPException(413, "too large")
    Path(real).write_text(body.text, encoding="utf-8", newline="")
    return {"ok": True}


@app.post("/uploads", dependencies=[Depends(auth)])
async def upload(request: Request, name: str = "project.zip"):
    uid = uuid.uuid4().hex[:10]
    path = UPLOAD_DIR / f"{uid}.zip"
    limit = int(CFG["max_upload_mb"]) * 1024 * 1024
    size = 0
    try:
        with open(path, "wb") as f:
            async for chunk in request.stream():
                size += len(chunk)
                if size > limit:
                    raise HTTPException(413, f"zip is larger than {CFG['max_upload_mb']} MB")
                f.write(chunk)
    except HTTPException:
        path.unlink(missing_ok=True)
        raise
    if not zipfile.is_zipfile(path):
        path.unlink(missing_ok=True)
        raise HTTPException(400, "that file is not a zip")
    (UPLOAD_DIR / f"{uid}.name").write_text(os.path.basename(name), encoding="utf-8")
    audit(request, "upload zip", f"{os.path.basename(name)} ({size // 1024} KB)")
    return {"upload_id": uid, "name": os.path.basename(name), "size": size}


@app.get("/inspect", dependencies=[Depends(auth)])
def inspect(folder: str, upload_id: Optional[str] = None):
    """What will happen: how many files, which scripts can run, which one is the default."""
    folder = safe_path(folder)
    scripts = {}
    overwrite = new = files = 0
    if upload_id:
        plan = zip_plan(upload_path(upload_id), folder)
        files = len(plan)
        for _, rel, dest in plan:
            if os.path.isfile(dest):
                overwrite += 1
            else:
                new += 1
            if rel.lower().endswith(SCRIPT_EXT) and rel.count("/") <= 1:
                scripts[rel] = "zip"
    for s in list_scripts(folder):
        scripts.setdefault(s, "folder")
    names = sorted(scripts, key=lambda s: (s.count("/"), s.lower()))
    return {"folder": folder, "files": files, "overwrite": overwrite, "new": new,
            "scripts": [{"path": n, "source": scripts[n]} for n in names], "default_script": pick_default(names)}


@app.post("/jobs", dependencies=[Depends(auth)])
def create_job(request: Request, body: JobIn):
    folder = safe_path(body.folder)
    os.makedirs(folder, exist_ok=True)
    if any(j.active() and os.path.normcase(j.folder) == os.path.normcase(folder) for j in JOBS.values()):
        raise HTTPException(409, "a job is already running in this folder")
    script = body.script.strip().replace("\\", "/")
    zip_name = ""
    if body.upload_id:
        upload_path(body.upload_id)
        nf = UPLOAD_DIR / f"{body.upload_id}.name"
        zip_name = nf.read_text(encoding="utf-8") if nf.exists() else "project.zip"
    if script:
        if _bad_member(script) or not script.lower().endswith(SCRIPT_EXT):
            raise HTTPException(400, "choose a .bat, .cmd or .ps1 script inside the project folder")
        if not body.upload_id and not os.path.isfile(os.path.join(folder, script)):
            raise HTTPException(404, f"{script} was not found in the folder")
    elif not body.upload_id:
        raise HTTPException(400, "nothing to do: send a zip or choose a script")
    title = body.title or (f"{script or 'unzip'} in {os.path.basename(folder)}")
    job = Job(folder, script, body.upload_id, title, zip_name)
    audit(request, "start job", f"folder={folder} script={script or '-'} zip={zip_name or '-'}")
    with JOBS_LOCK:
        JOBS[job.id] = job
    threading.Thread(target=job_worker, args=(job,), daemon=True).start()
    return job.summary()


@app.get("/jobs", dependencies=[Depends(auth)])
def list_jobs():
    with JOBS_LOCK:
        items = sorted(JOBS.values(), key=lambda j: j.created, reverse=True)
    return {"jobs": [j.summary() for j in items[:60]]}


def get_job(job_id: str) -> Job:
    j = JOBS.get(job_id)
    if not j:
        raise HTTPException(404, "job not found")
    return j


@app.get("/jobs/{job_id}", dependencies=[Depends(auth)])
def job_status(job_id: str, since: int = 0):
    j = get_job(job_id)
    with j.lock:
        chunk = j.lines[since:since + 400]
    return j.summary() | {"lines": chunk, "next": since + len(chunk)}


@app.post("/jobs/{job_id}/input", dependencies=[Depends(auth)])
def job_input(request: Request, job_id: str, body: InputIn):
    audit(request, "typed answer", f"job {job_id} ({len(body.text)} characters, text not logged)")
    get_job(job_id).send_input(body.text)
    return {"ok": True}


@app.post("/jobs/{job_id}/stop", dependencies=[Depends(auth)])
def job_stop(request: Request, job_id: str):
    j = get_job(job_id)
    audit(request, "stop job", j.title)
    j.stop_flag.set()
    for p in list(j.procs):
        kill_tree(p)
    return {"ok": True}


@app.post("/jobs/{job_id}/rollback", dependencies=[Depends(auth)])
def job_rollback(request: Request, job_id: str):
    j = get_job(job_id)
    audit(request, "roll back", j.title)
    if j.active():
        raise HTTPException(409, "stop the job first")
    rollback(j)
    save_jobs()
    return {"ok": True}


@app.get("/jobs/{job_id}/report", dependencies=[Depends(auth)])
def job_report(job_id: str):
    j = get_job(job_id)
    try:
        listing = ", ".join(sorted(os.listdir(j.folder))[:40])
    except OSError:
        listing = "?"
    with j.lock:
        log = "\n".join(f"[{l['k']}] {l['s']}" for l in j.lines[-200:])
    with j.lock:
        snapshot = list(j.lines)
    text = (f"## Remoty error report\n{problems_text(parse_problems(snapshot))}Task: {j.title}\nFolder: {j.folder}\nScript: {j.script}\nZip: {j.zip_name}\n"
            f"Status: {j.status} (exit code {j.exit_code})\nOS: {sys.platform}\n"
            f"Top-level files: {listing}\n\n### Last 200 log lines\n```\n{log}\n```\n")
    return PlainTextResponse(text)


@app.get("/jobs/{job_id}/problems", dependencies=[Depends(auth)])
def job_problems(job_id: str):
    j = get_job(job_id)
    with j.lock:
        snapshot = list(j.lines[-4000:])
    return {"problems": parse_problems(snapshot)}


@app.get("/jobs/{job_id}/artifact", dependencies=[Depends(auth)])
def job_artifact(request: Request, job_id: str):
    j = get_job(job_id)
    audit(request, "download APK", j.artifact or "")
    if not j.artifact or not os.path.isfile(j.artifact):
        raise HTTPException(404, "no artifact")
    safe_path(j.artifact)
    return FileResponse(j.artifact, filename=os.path.basename(j.artifact),
                        media_type="application/vnd.android.package-archive")


APK_DIRS_FILE = DATA_DIR / "apk_dirs.json"
PRUNE = {".git", "node_modules", ".gradle", "windows", "program files", "program files (x86)", "programdata",
         "$recycle.bin", "system volume information", "appdata", "recovery", "perflogs", "msocache"}


def known_apk_dirs() -> list:
    try:
        return [d for d in json.loads(APK_DIRS_FILE.read_text(encoding="utf-8-sig")) if os.path.isdir(d)]
    except Exception:
        return []


def remember_apk_dir(d: str):
    dirs = [x for x in known_apk_dirs() if os.path.normcase(x) != os.path.normcase(d)]
    try:
        APK_DIRS_FILE.write_text(json.dumps([d] + dirs[:9]), encoding="utf-8")
    except OSError:
        pass


def _scan_apks(base: str, pat, depth_max: int, deadline: float, best):
    for dp, dn, fn in os.walk(base):
        if time.time() > deadline:
            break
        depth = os.path.relpath(dp, base).count(os.sep) if dp != base else 0
        dn[:] = [d for d in dn if d.lower() not in PRUNE and depth < depth_max]
        for f in fn:
            m = pat.fullmatch(f)
            if m:
                full = os.path.join(dp, f)
                try:
                    key = (int(m.group(1)), os.path.getmtime(full))
                except OSError:
                    continue
                if best is None or key > best[0]:
                    best = (key, full)
    return best


@app.get("/latest-apk", dependencies=[Depends(auth)])
def latest_apk(request: Request, prefix: str = "Remoty-build"):
    audit(request, "check for update", prefix, every=30)
    """The newest <prefix><number>.apk. Looks in the folders it already knows (where one was found before, the allowed
    folders, folders jobs ran in), and if there is none, searches every drive by name (a few seconds, names only)."""
    pat = re.compile(re.escape(prefix) + r"(\d+)\.apk", re.I)
    places = list(dict.fromkeys(known_apk_dirs() + list(CFG["roots"]) + [j.folder for j in list(JOBS.values())]))
    best = None
    deadline = time.time() + 5
    for r in places:
        if os.path.isdir(r):
            best = _scan_apks(os.path.realpath(r), pat, 4, deadline, best)
    if best is None:
        deadline = time.time() + 12
        for d in drive_roots():
            best = _scan_apks(d, pat, 5, deadline, best)
    if not best:
        raise HTTPException(404, "no Remoty APK found on the laptop yet")
    remember_apk_dir(os.path.dirname(best[1]))
    return {"build": best[0][0], "name": os.path.basename(best[1]), "path": best[1], "mtime": best[0][1]}


@app.get("/apk", dependencies=[Depends(auth)])
def get_apk(request: Request, path: str):
    audit(request, "download APK", path)
    real = os.path.realpath(path)
    if not real.lower().endswith(".apk") or not os.path.isfile(real):
        raise HTTPException(404, "no such APK")
    try:
        safe_path(path)
    except HTTPException:
        # outside the allowed folders: only a Remoty-buildN.apk in a folder where the search found one
        known = [os.path.normcase(os.path.realpath(d)) for d in known_apk_dirs()]
        if not (re.fullmatch(r"Remoty-build\d+\.apk", os.path.basename(real), re.I) and os.path.normcase(os.path.dirname(real)) in known):
            raise
    return FileResponse(real, filename=os.path.basename(real), media_type="application/vnd.android.package-archive")


@app.get("/screenshot", dependencies=[Depends(auth)])
def screenshot(request: Request, w: int = 1000, q: int = 70):
    audit(request, "screen view", "", every=60)
    try:
        import mss
        from PIL import Image
        with mss.mss() as sct:
            shot = sct.grab(sct.monitors[1])
            img = Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
        w = max(300, min(w, 2000))
        if img.width > w:
            img = img.resize((w, int(img.height * w / img.width)))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=max(30, min(q, 90)))
        return Response(buf.getvalue(), media_type="image/jpeg")
    except Exception as e:
        raise HTTPException(500, f"screenshot failed: {e}")


class RemoteIn(BaseModel):
    action: str
    x: float = 0.5
    y: float = 0.5
    dy: float = 0.0
    text: str = ""
    key: str = ""
    mods: list = []


@app.post("/remote", dependencies=[Depends(auth)])
def remote(request: Request, body: RemoteIn):
    if body.action == "text":
        audit(request, "remote typing", f"{len(body.text)} characters (text not logged)")
    elif body.action == "key":
        audit(request, "remote key", "+".join(list(body.mods) + [body.key]))
    elif body.action in ("move", "scroll", "down", "up"):
        audit(request, "remote mouse", body.action, every=5)
    else:
        audit(request, "remote " + body.action, f"x={body.x:.2f} y={body.y:.2f}")
    do_remote(body.model_dump())
    return {"ok": True}


@app.get("/audit", dependencies=[Depends(auth)])
def get_audit(limit: int = 200):
    return {"entries": audit_tail(max(1, min(limit, 1000)))}


@app.get("/diagnostics", dependencies=[Depends(auth)])
def diagnostics():
    disks = {}
    for d in (drive_roots() if IS_WIN else ["/"]):
        try:
            disks[d] = f"{shutil.disk_usage(d).free // (1024 ** 3)} GB free"
        except OSError:
            pass
    return PlainTextResponse("\n".join([
        f"Agent {VERSION}  (frozen exe: {bool(getattr(sys, 'frozen', False))})",
        f"Python {platform.python_version()}   {platform.platform()}",
        f"Name {CFG['name']}   listening {BIND}:{CFG['port']}   ZeroTier address {ZT_IP or 'NOT DETECTED'}",
        f"Uptime {int(time.time() - STARTED_AT) // 60} min   jobs {len(JOBS)} (running {sum(1 for j in JOBS.values() if j.active())})",
        f"Allowed folders: {', '.join(root_list())}   all_drives={bool(CFG.get('all_drives'))}",
        f"remote_control={bool(CFG.get('remote_control'))}   keep_awake={bool(CFG.get('keep_awake', True))} (awake now: {keeping_awake()})",
        f"Tools: " + ", ".join(f"{t}={'yes' if tool(t) else 'no'}" for t in ("git", "gh", "powershell", "java")),
        f"Disks: {disks}",
        "Recent activity:",
    ] + [f"  {e['ts']} {e['action']}: {e['detail']}" for e in audit_tail(12)]))


@app.exception_handler(Exception)
async def on_error(_, exc):
    return JSONResponse({"detail": f"{type(exc).__name__}: {exc}"}, status_code=500)


def make_server():
    """Creates (does not start) the uvicorn server; the tray app runs it in a thread and stops it with should_exit."""
    import uvicorn
    cfg = uvicorn.Config(app, host=BIND, port=CFG["port"], log_level="warning", log_config=None)
    return uvicorn.Server(cfg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--new-token", action="store_true", help="generate a new pairing token and exit")
    args = ap.parse_args()
    if args.new_token:
        CFG["token"] = new_token()
        CONFIG_PATH.write_text(json.dumps(CFG, indent=2), encoding="utf-8")
        print("New token:", CFG["token"])
        return
    print("=" * 60)
    print(f" Remoty Agent {VERSION}   name: {CFG['name']}")
    print(f" listening on {BIND}:{CFG['port']}" + ("" if ZT_IP else "   (NO ZeroTier adapter found: listening on all networks)"))
    print(f" PAIRING TOKEN : {CFG['token']}")
    print(f" allowed folders: {', '.join(root_list())}")
    print(f" git: {tool('git') or 'not found'}   gh: {tool('gh') or 'not found'}")
    print("=" * 60)
    load_jobs_once()
    make_server().run()


if __name__ == "__main__":
    main()
