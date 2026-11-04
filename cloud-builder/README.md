# Cloud ZIP-to-APK Builder

This is an optional self-hosted build service. It is not a free hosted service and is not deployed by committing this source code.

## Requirements

- A Linux VM you control with Docker Engine and Python 3.11+
- At least 8 GB RAM and enough disk space for temporary Android builds
- HTTPS reverse proxy (for example Caddy or nginx) in front of the API
- A long random `BUILDER_TOKEN`; never use an AI provider API key as this token

## Install

From the repository root:

```bash
docker build -t android-code-agent-builder:1 -f cloud-builder/worker/Dockerfile cloud-builder/worker
sudo mkdir -p /var/lib/aca-builds
sudo chown "$USER" /var/lib/aca-builds
python3 -m venv .venv
. .venv/bin/activate
pip install -r cloud-builder/requirements.txt
export BUILDER_TOKEN='replace-with-a-long-random-secret'
export ANDROID_BUILDER_IMAGE='android-code-agent-builder:1'
export TMPDIR='/var/lib/aca-builds'
cd cloud-builder
uvicorn app:app --host 127.0.0.1 --port 8080
```

Keep the API bound to localhost and expose it only through an HTTPS reverse proxy. Do not expose port 8080 directly to the public internet. Restrict access with firewall rules and rotate the token if it is exposed.

## API

- `POST /v1/builds`: multipart form with `file` (ZIP) and optional `project_dir` (default `.`); requires `Authorization: Bearer <BUILDER_TOKEN>`.
- `GET /v1/builds/{build_id}`: returns queued/running/completed/failed status and a bounded log excerpt.
- `GET /v1/builds/{build_id}/artifact`: downloads the APK ZIP after a successful build.

The worker accepts ZIPs up to 100 MB, limits expanded files and file count, rejects path traversal and symbolic links, runs one build at a time, uses a 35-minute timeout and fixed `assembleDebug` task. Builds run in disposable Docker containers with CPU, memory and process limits, dropped Linux capabilities and no-new-privileges. No provider API keys or signing credentials are passed into the build container.

## Important limitations

The service stores job state in memory and temporary files on the VM. Restarting the API process loses job status; clean `/var/lib/aca-builds` periodically. Uploaded Gradle projects execute their own build scripts inside the container and have network access to download dependencies. Do not place secrets in uploaded projects. Use a dedicated VM and do not share it with sensitive workloads. This is a starting worker, not a hardened multi-tenant public SaaS service.
