import asyncio
import hmac
import os
import shutil
import tempfile
import time
import uuid
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse

TOKEN = os.environ.get("BUILDER_TOKEN", "")
WORKER_IMAGE = os.environ.get("ANDROID_BUILDER_IMAGE", "android-code-agent-builder:1")
MAX_UPLOAD = 100 * 1024 * 1024
MAX_EXPANDED = 300 * 1024 * 1024
MAX_FILES = 5000
ALLOWED_GRADLE_TASKS = {"tasks", "assembleDebug", "testDebugUnitTest", "lintDebug", "check", "clean"}
JOBS: dict[str, dict[str, Any]] = {}
LOCK = asyncio.Semaphore(1)
app = FastAPI(title="Android Code Agent Isolated Builder", version="1.0")


def require_auth(header: str | None) -> None:
    if not TOKEN:
        raise HTTPException(503, "Builder is not configured with BUILDER_TOKEN")
    supplied = (header or "").removeprefix("Bearer ").strip()
    if not hmac.compare_digest(supplied, TOKEN):
        raise HTTPException(401, "Invalid builder token")


def safe_extract(archive: Path, destination: Path) -> None:
    total = 0
    with zipfile.ZipFile(archive) as zf:
        entries = zf.infolist()
        if len(entries) > MAX_FILES:
            raise ValueError("ZIP contains too many entries")
        for item in entries:
            name = item.filename.replace("\\", "/")
            rel = PurePosixPath(name)
            mode = (item.external_attr >> 16) & 0o170000
            if rel.is_absolute() or any(part in ("", ".", "..") for part in rel.parts):
                if name.endswith("/") and rel.parts and all(p not in ("..",) for p in rel.parts):
                    pass
                else:
                    raise ValueError("ZIP contains an unsafe path")
            if mode == 0o120000:
                raise ValueError("ZIP symbolic links are not accepted")
            total += item.file_size
            if total > MAX_EXPANDED:
                raise ValueError("Expanded ZIP exceeds the size limit")
            target = destination.joinpath(*rel.parts).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError("ZIP path escapes the workspace")
            if item.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(item) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)


def project_root(source: Path, requested: str) -> Path:
    if requested.startswith("/") or ".." in PurePosixPath(requested).parts:
        raise ValueError("Invalid project folder")
    root = (source / requested).resolve()
    if not root.is_relative_to(source.resolve()) or not root.is_dir():
        raise ValueError("Project folder is outside the uploaded source or does not exist")
    if not (root / "settings.gradle").exists() and not (root / "settings.gradle.kts").exists() and not (root / "build.gradle").exists() and not (root / "build.gradle.kts").exists():
        raise ValueError("No Gradle project files found in the selected folder")
    return root


async def execute_job(job_id: str) -> None:
    job = JOBS[job_id]
    async with LOCK:
        job["status"] = "running"
        try:
            source = Path(job["source"])
            project = Path(job["project"])
            work = Path(job["work"])
            task = job["task"]
            wrapper = project / "gradlew"
            if wrapper.is_file():
                gradle_command = ["bash", str(wrapper), "--no-daemon", "--console=plain", task]
            else:
                gradle_command = ["gradle", "--no-daemon", "--console=plain", task]
            command = [
                "docker", "run", "--rm",
                "--network=bridge",
                "--cpus=2", "--memory=5g", "--pids-limit=256",
                "--security-opt=no-new-privileges", "--cap-drop=ALL",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=1g",
                "--tmpfs", "/root/.gradle:rw,nosuid,nodev,size=2g",
                "--mount", "type=bind,src=" + str(source) + ",dst=/workspace",
                "--mount", "type=bind,src=" + str(work / "outputs") + ",dst=/outputs",
                "--workdir", "/workspace/" + project.relative_to(source).as_posix(),
                WORKER_IMAGE,
                *gradle_command
            ]
            process = await asyncio.create_subprocess_exec(
                *command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
            )
            async def stream_output() -> None:
                assert process.stdout is not None
                while True:
                    line = await process.stdout.readline()
                    if not line:
                        break
                    current = job.get("log", "")
                    job["log"] = (current + line.decode("utf-8", errors="replace"))[-200000:]
                    job["updated_at"] = int(time.time())
            try:
                await asyncio.wait_for(asyncio.gather(process.wait(), stream_output()), timeout=2100)
            except asyncio.TimeoutError:
                process.kill()
                await process.wait()
                raise RuntimeError("Build command exceeded the 35 minute time limit")
            if process.returncode != 0:
                raise RuntimeError(f"Gradle task {task} exited with code {process.returncode}")
            if task == "assembleDebug":
                apks = [p for p in source.glob("**/build/outputs/apk/**/*.apk") if "androidTest" not in p.parts]
                if not apks:
                    raise RuntimeError("Gradle finished but no APK was produced")
                artifact = work / "outputs" / "android-code-agent-apks.zip"
                with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as zf:
                    for apk in apks:
                        zf.write(apk, apk.relative_to(source).as_posix())
                job["artifact"] = str(artifact)
            job["status"] = "completed"
        except Exception as exc:
            job["status"] = "failed"
            job["error"] = str(exc)
            if not job.get("log"):
                job["log"] = str(exc)
        finally:
            job["updated_at"] = int(time.time())


@app.get("/v1/environment")
async def get_environment(authorization: str | None = Header(default=None)):
    require_auth(authorization)
    command = [
        "docker", "run", "--rm", "--network=none", "--cpus=1", "--memory=1g",
        "--pids-limit=64", "--security-opt=no-new-privileges", "--cap-drop=ALL",
        WORKER_IMAGE, "bash", "-lc",
        "java -version 2>&1; gradle --version | head -n 6; sdkmanager --list_installed 2>/dev/null | grep -E 'platforms;|build-tools;' | tail -n 20"
    ]
    try:
        process = await asyncio.create_subprocess_exec(*command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        output, _ = await asyncio.wait_for(process.communicate(), timeout=90)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()
        raise HTTPException(504, "Android SDK environment check timed out")
    except Exception as exc:
        raise HTTPException(503, f"Android SDK environment unavailable: {exc}")
    if process.returncode != 0:
        raise HTTPException(503, output.decode("utf-8", errors="replace")[-4000:])
    return {"status": "ready", "sdk_root": "/opt/android-sdk", "output": output.decode("utf-8", errors="replace")[-8000:]}


@app.post("/v1/builds")
async def create_build(
    file: UploadFile = File(...),
    project_dir: str = Form("."),
    gradle_task: str = Form("assembleDebug"),
    authorization: str | None = Header(default=None),
):
    require_auth(authorization)
    if not file.filename or not file.filename.lower().endswith(".zip"):
        raise HTTPException(400, "Upload a ZIP project archive")
    if gradle_task not in ALLOWED_GRADLE_TASKS:
        raise HTTPException(400, "Gradle task is not in the allowed command list")
    job_id = uuid.uuid4().hex
    work = Path(tempfile.mkdtemp(prefix="aca-build-"))
    source = work / "source"
    outputs = work / "outputs"
    source.mkdir()
    outputs.mkdir()
    archive = work / "upload.zip"
    size = 0
    try:
        with archive.open("wb") as target:
            while True:
                chunk = await file.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_UPLOAD:
                    raise HTTPException(413, "ZIP upload exceeds 100 MB")
                target.write(chunk)
        await file.close()
        safe_extract(archive, source)
        project = project_root(source, project_dir)
    except HTTPException:
        shutil.rmtree(work, ignore_errors=True)
        raise
    except Exception as exc:
        shutil.rmtree(work, ignore_errors=True)
        raise HTTPException(400, str(exc))
    archive.unlink(missing_ok=True)
    JOBS[job_id] = {
        "status": "queued", "created_at": int(time.time()), "updated_at": int(time.time()),
        "work": str(work), "source": str(source), "project": str(project), "log": "", "task": gradle_task
    }
    asyncio.create_task(execute_job(job_id))
    return {"build_id": job_id, "status": "queued"}


@app.get("/v1/builds/{job_id}")
async def get_build(job_id: str, authorization: str | None = Header(default=None)):
    require_auth(authorization)
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "Build not found")
    return {
        "build_id": job_id, "status": job["status"], "task": job.get("task"), "error": job.get("error"),
        "log": job.get("log", "")[-20000:], "updated_at": job["updated_at"],
        "artifact_ready": bool(job.get("artifact"))
    }


@app.get("/v1/builds/{job_id}/artifact")
async def get_artifact(job_id: str, authorization: str | None = Header(default=None)):
    require_auth(authorization)
    job = JOBS.get(job_id)
    if not job or job.get("status") != "completed" or not job.get("artifact"):
        raise HTTPException(404, "APK artifact is not ready")
    return FileResponse(job["artifact"], filename="android-code-agent-apks.zip", media_type="application/zip")
