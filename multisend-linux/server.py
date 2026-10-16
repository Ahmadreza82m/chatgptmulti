from __future__ import annotations

import asyncio
import uuid
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from playwright.async_api import BrowserContext, Page, Playwright, async_playwright

ROOT = Path(__file__).resolve().parent
SESSIONS = ROOT / "sessions"
OUTPUTS = ROOT / "outputs"
WEB = ROOT / "web"
SESSIONS.mkdir(exist_ok=True)
OUTPUTS.mkdir(exist_ok=True)

app = FastAPI(title="MultiSend Control Center")
playwright: Playwright | None = None
contexts: dict[str, BrowserContext] = {}
jobs: dict[str, dict[str, Any]] = {}
stop_event = asyncio.Event()


async def ensure_playwright() -> None:
    global playwright
    if playwright is None:
        playwright = await async_playwright().start()


async def get_context(session_id: str) -> BrowserContext:
    await ensure_playwright()
    existing = contexts.get(session_id)
    if existing:
        return existing
    profile = SESSIONS / session_id
    profile.mkdir(parents=True, exist_ok=True)
    context = await playwright.chromium.launch_persistent_context(
        user_data_dir=str(profile),
        headless=False,
        accept_downloads=True,
        args=["--start-maximized"],
        viewport={"width": 1280, "height": 760},
    )
    contexts[session_id] = context
    return context


async def first_page(context: BrowserContext) -> Page:
    return context.pages[0] if context.pages else await context.new_page()


async def wait_for_chat(page: Page) -> None:
    if "chatgpt.com" not in page.url:
        await page.goto("https://chatgpt.com/", wait_until="domcontentloaded", timeout=60_000)
    await page.wait_for_timeout(1_500)


async def submit_job(job: dict[str, Any]) -> None:
    try:
        job["status"] = "STARTING"
        context = await get_context(job["session"])
        page = await first_page(context)
        await wait_for_chat(page)
        job["status"] = "WAITING_FOR_LOGIN"

        composer = page.locator("textarea").first
        if not await composer.is_visible(timeout=5_000):
            composer = page.locator("[contenteditable='true']").first
        await composer.wait_for(state="visible", timeout=120_000)

        if stop_event.is_set():
            job["status"] = "CANCELLED"
            return

        job["status"] = "UPLOADING"
        file_input = page.locator("input[type='file']").first
        if await file_input.count() == 0:
            buttons = page.get_by_role("button")
            for i in range(min(await buttons.count(), 40)):
                button = buttons.nth(i)
                label = ((await button.get_attribute("aria-label")) or "").lower()
                if "attach" in label or "upload" in label:
                    await button.click()
                    break
            file_input = page.locator("input[type='file']").first
        await file_input.set_input_files(job["image"])
        await composer.fill(job["prompt"])

        job["status"] = "GENERATING"
        await composer.press("Enter")

        # Each browser profile is independent. No Android app is opened, closed,
        # foregrounded or clicked by this engine.
        for _ in range(180):
            if stop_event.is_set():
                job["status"] = "CANCELLED"
                return
            images = await page.locator("img[src]").evaluate_all("els => els.map(e => e.src)")
            candidates = [x for x in images if isinstance(x, str) and x.startswith(("http://", "https://", "blob:"))]
            if len(candidates) > 1:
                job["result"] = candidates[-1]
                job["status"] = "COMPLETED"
                return
            await page.wait_for_timeout(1_000)

        job["status"] = "FAILED"
        job["error"] = "Generation timeout"
    except Exception as exc:
        job["status"] = "FAILED"
        job["error"] = str(exc)[:500]


@app.get("/", response_class=HTMLResponse)
async def index() -> str:
    return (WEB / "index.html").read_text(encoding="utf-8")


@app.get("/app.js", response_class=FileResponse)
async def js() -> FileResponse:
    return FileResponse(WEB / "app.js", media_type="text/javascript")


@app.get("/style.css", response_class=FileResponse)
async def css() -> FileResponse:
    return FileResponse(WEB / "style.css", media_type="text/css")


@app.get("/api/jobs")
async def get_jobs() -> list[dict[str, Any]]:
    return [{k: v for k, v in job.items() if k != "image"} for job in jobs.values()]


@app.post("/api/stop")
async def stop() -> dict[str, str]:
    stop_event.set()
    for job in jobs.values():
        if job.get("status") not in {"COMPLETED", "FAILED", "CANCELLED"}:
            job["status"] = "CANCELLED"
    return {"status": "STOPPED"}


@app.post("/api/reset")
async def reset() -> dict[str, str]:
    stop_event.clear()
    jobs.clear()
    return {"status": "READY"}


@app.post("/api/batch")
async def batch(prompt: str = Form(...), sessions: str = Form(...), images: list[UploadFile] = File(...)) -> dict[str, Any]:
    stop_event.clear()
    session_list = [s.strip() for s in sessions.split(",") if s.strip()]
    if len(session_list) < len(images):
        raise ValueError("One browser session is required for every image")

    created = []
    for index, upload in enumerate(images):
        job_id = uuid.uuid4().hex[:10]
        path = OUTPUTS / f"input_{job_id}_{upload.filename or 'image'}"
        path.write_bytes(await upload.read())
        jobs[job_id] = {
            "id": job_id, "index": index, "session": session_list[index],
            "filename": upload.filename, "image": str(path), "prompt": prompt,
            "status": "QUEUED", "error": "", "result": ""
        }
        created.append(job_id)

    # All jobs are scheduled together; one failure cannot cancel another job.
    await asyncio.gather(*(submit_job(jobs[job_id]) for job_id in created), return_exceptions=True)
    return {"jobs": created}


@app.on_event("shutdown")
async def shutdown() -> None:
    global playwright
    for context in contexts.values():
        await context.close()
    contexts.clear()
    if playwright:
        await playwright.stop()
    playwright = None


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="127.0.0.1", port=8787, reload=False)
