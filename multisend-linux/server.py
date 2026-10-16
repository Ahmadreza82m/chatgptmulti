from __future__ import annotations

import asyncio
import os
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

browser: Any = None
playwright: Playwright | None = None
contexts: dict[str, BrowserContext] = {}
jobs: dict[str, dict[str, Any]] = {}
lock = asyncio.Lock()
stop_event = asyncio.Event()


def page_url(page: Page) -> str:
    return page.url or ""


async def ensure_browser() -> None:
    global browser, playwright
    if browser is not None:
        return
    playwright = await async_playwright().start()
    browser = await playwright.chromium.launch(headless=True)


async def get_context(session_id: str) -> BrowserContext:
    await ensure_browser()
    if session_id not in contexts:
        profile = SESSIONS / session_id
        contexts[session_id] = await browser.new_context(accept_downloads=True, storage_state=None)
        # A persistent profile is intentionally not required for the first test.
        # Login state can later be imported into each session from the GUI.
    return contexts[session_id]


async def wait_for_chat(page: Page) -> None:
    await page.goto("https://chatgpt.com/", wait_until="domcontentloaded", timeout=60_000)
    await page.wait_for_timeout(2_000)


async def submit_job(job: dict[str, Any]) -> None:
    job_id = job["id"]
    session = job["session"]
    try:
        job["status"] = "STARTING"
        context = await get_context(session)
        page = await context.new_page()
        job["page"] = page
        await wait_for_chat(page)
        job["status"] = "WAITING_FOR_LOGIN"

        # This is deliberately conservative: no coordinate taps and no Android
        # foreground switching. The browser session remains isolated from the phone UI.
        composer = page.locator("textarea").first
        if not await composer.is_visible(timeout=5_000):
            composer = page.locator("[contenteditable='true']").first
        await composer.wait_for(state="visible", timeout=120_000)

        job["status"] = "UPLOADING"
        file_input = page.locator("input[type='file']").first
        if await file_input.count():
            await file_input.set_input_files(job["image"])
        else:
            # ChatGPT UI variants expose an attachment button. Click it and then
            # use the resulting file input when available.
            await page.get_by_role("button", name=lambda n: n and "attach" in n.lower()).click(timeout=5_000)
            file_input = page.locator("input[type='file']").first
            await file_input.set_input_files(job["image"])

        await composer.fill(job["prompt"])
        job["status"] = "GENERATING"
        await composer.press("Enter")

        # Wait for a new image/result link. We never switch Android apps.
        for _ in range(180):
            if stop_event.is_set():
                job["status"] = "CANCELLED"
                return
            links = await page.locator("a[href]").evaluate_all("els => els.map(e => e.href)")
            images = await page.locator("img[src]").evaluate_all("els => els.map(e => e.src)")
            candidates = [x for x in links + images if isinstance(x, str) and ("image" in x or "blob:" in x or "oaidalleapiprodscus" in x)]
            if candidates:
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
    return [
        {k: v for k, v in job.items() if k not in {"page", "image"}}
        for job in jobs.values()
    ]


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
async def batch(
    prompt: str = Form(...),
    sessions: str = Form(...),
    images: list[UploadFile] = File(...),
) -> dict[str, Any]:
    stop_event.clear()
    session_list = [s.strip() for s in sessions.split(",") if s.strip()]
    if len(session_list) < len(images):
        raise ValueError("One browser session is required for every image")

    created = []
    for index, upload in enumerate(images):
        job_id = uuid.uuid4().hex[:10]
        path = OUTPUTS / f"input_{job_id}_{upload.filename or 'image'}"
        path.write_bytes(await upload.read())
        job = {
            "id": job_id,
            "index": index,
            "session": session_list[index],
            "filename": upload.filename,
            "image": str(path),
            "prompt": prompt,
            "status": "QUEUED",
            "error": "",
            "result": "",
        }
        jobs[job_id] = job
        created.append(job_id)

    # True concurrent dispatch: all independent browser tasks are scheduled together.
    await asyncio.gather(*(submit_job(jobs[job_id]) for job_id in created))
    return {"jobs": created}


@app.on_event("shutdown")
async def shutdown() -> None:
    global browser, playwright
    for context in contexts.values():
        await context.close()
    contexts.clear()
    if browser is not None:
        await browser.close()
    browser = None
    if playwright is not None:
        await playwright.stop()
    playwright = None


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="127.0.0.1", port=8787, reload=False)
