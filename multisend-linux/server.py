from __future__ import annotations

import asyncio
import json
import re
import uuid
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse
from playwright.async_api import BrowserContext, Page, Playwright, TimeoutError as PlaywrightTimeoutError, async_playwright

ROOT = Path(__file__).resolve().parent
SESSIONS = ROOT / "sessions"
UPLOADS = ROOT / "uploads"
WEB = ROOT / "web"
SESSIONS.mkdir(exist_ok=True)
UPLOADS.mkdir(exist_ok=True)

ACCOUNT_IDS = [f"account-{i:02d}" for i in range(1, 6)]
GEMINI_URL = "https://gemini.google.com/app"
MAX_IMAGE_BYTES = 25 * 1024 * 1024
MAX_IMAGES_PER_ACCOUNT = 2

app = FastAPI(title="MultiSend Gemini Session Manager")
playwright: Playwright | None = None
contexts: dict[str, BrowserContext] = {}
jobs: dict[str, dict[str, Any]] = {}
batch_task: asyncio.Task | None = None
batch_lock = asyncio.Lock()
stop_event = asyncio.Event()


async def ensure_playwright() -> None:
    global playwright
    if playwright is None:
        playwright = await async_playwright().start()


def validate_account(account_id: str) -> None:
    if account_id not in ACCOUNT_IDS:
        raise HTTPException(status_code=404, detail="Unknown account slot")


async def get_context(account_id: str) -> BrowserContext:
    validate_account(account_id)
    await ensure_playwright()
    existing = contexts.get(account_id)
    if existing:
        return existing
    profile = SESSIONS / account_id
    profile.mkdir(parents=True, exist_ok=True)
    context = await playwright.chromium.launch_persistent_context(
        user_data_dir=str(profile),
        headless=False,
        accept_downloads=True,
        viewport={"width": 1365, "height": 900},
        args=["--start-maximized"],
    )
    contexts[account_id] = context
    return context


async def account_page(account_id: str) -> Page:
    context = await get_context(account_id)
    page = context.pages[0] if context.pages else await context.new_page()
    if "gemini.google.com" not in page.url:
        await page.goto(GEMINI_URL, wait_until="domcontentloaded", timeout=60_000)
    return page


async def find_composer(page: Page):
    for selector in ["rich-textarea [contenteditable='true']", "div[contenteditable='true']", "textarea"]:
        locator = page.locator(selector).last
        try:
            if await locator.is_visible(timeout=1_000):
                return locator
        except Exception:
            pass
    return None


async def account_logged_in(account_id: str) -> bool:
    context = contexts.get(account_id)
    if not context or not context.pages:
        return False
    page = context.pages[0]
    if "accounts.google.com" in page.url or "ServiceLogin" in page.url:
        return False
    return await find_composer(page) is not None


@app.get("/", response_class=HTMLResponse)
async def index() -> str:
    return (WEB / "index.html").read_text(encoding="utf-8")


@app.get("/app.js", response_class=FileResponse)
async def js() -> FileResponse:
    return FileResponse(WEB / "app.js", media_type="text/javascript")


@app.get("/style.css", response_class=FileResponse)
async def css() -> FileResponse:
    return FileResponse(WEB / "style.css", media_type="text/css")


@app.get("/api/accounts")
async def list_accounts() -> list[dict[str, Any]]:
    return [{
        "id": account_id,
        "name": f"حساب Gemini {number}",
        "opened": account_id in contexts,
        "logged_in": await account_logged_in(account_id),
    } for number, account_id in enumerate(ACCOUNT_IDS, start=1)]


@app.post("/api/accounts/{account_id}/open")
async def open_account(account_id: str) -> dict[str, str]:
    validate_account(account_id)
    try:
        page = await account_page(account_id)
        await page.bring_to_front()
        return {"status": "OPENED", "message": "پنجره حساب باز شد؛ ورود Google را خودت انجام بده."}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)[:300])


async def submit_job(job: dict[str, Any]) -> None:
    try:
        job["status"] = "OPENING_ACCOUNT"
        page = await account_page(job["account_id"])
        await page.bring_to_front()
        job["status"] = "WAITING_FOR_LOGIN"
        composer = None
        for _ in range(180):
            if stop_event.is_set():
                job["status"] = "CANCELLED"
                return
            composer = await find_composer(page)
            if composer is not None:
                break
            await page.wait_for_timeout(1_000)
        if composer is None:
            job["status"] = "FAILED"
            job["error"] = "ورود به Gemini کامل نشد یا کادر پیام پیدا نشد."
            return

        job["status"] = "ATTACHING_IMAGES"
        file_input = page.locator("input[type='file']").last
        try:
            await file_input.wait_for(state="attached", timeout=8_000)
        except PlaywrightTimeoutError:
            attach = page.get_by_role("button", name=re.compile("attach|upload|افزودن", re.I)).first
            if await attach.count():
                await attach.click()
            file_input = page.locator("input[type='file']").last
            await file_input.wait_for(state="attached", timeout=10_000)
        await file_input.set_input_files(job["images"])
        await page.wait_for_timeout(1_500)
        composer = await find_composer(page)
        if composer is None:
            raise RuntimeError("کادر نوشتن پیام Gemini در دسترس نیست.")
        await composer.fill(job["prompt"])
        if stop_event.is_set():
            job["status"] = "CANCELLED"
            return

        before_responses = await page.locator("model-response").count()
        job["status"] = "SENDING"
        send_button = page.get_by_role("button", name=re.compile("send|ارسال", re.I)).last
        try:
            if await send_button.is_visible(timeout=1_500):
                await send_button.click()
            else:
                await composer.press("Enter")
        except Exception:
            await composer.press("Enter")

        job["status"] = "WAITING_FOR_RESULT"
        for _ in range(180):
            if stop_event.is_set():
                job["status"] = "CANCELLED"
                return
            if await page.locator("model-response").count() > before_responses:
                job["status"] = "COMPLETED"
                job["result_url"] = page.url
                return
            await page.wait_for_timeout(1_000)

        job["status"] = "VERIFY_IN_GEMINI"
        job["result_url"] = page.url
        job["error"] = "درخواست ارسال شد؛ پایان پاسخ خودکار تشخیص داده نشد. نتیجه را در Gemini بررسی کن."
    except Exception as exc:
        job["status"] = "FAILED"
        job["error"] = str(exc)[:500]


async def run_batch(job_ids: list[str]) -> None:
    async with batch_lock:
        for job_id in job_ids:
            if stop_event.is_set():
                break
            await submit_job(jobs[job_id])


@app.get("/api/jobs")
async def get_jobs() -> list[dict[str, Any]]:
    return [{k: v for k, v in job.items() if k != "images"} for job in jobs.values()]


@app.post("/api/batch")
async def create_batch(
    prompt: str = Form(...),
    assignments: str = Form(...),
    images: list[UploadFile] = File(...),
) -> dict[str, Any]:
    global batch_task
    if batch_task and not batch_task.done():
        raise HTTPException(status_code=409, detail="یک صف در حال اجراست؛ ابتدا آن را تمام یا متوقف کن.")
    if not prompt.strip():
        raise HTTPException(status_code=400, detail="پرامپت خالی است.")
    if not images:
        raise HTTPException(status_code=400, detail="هیچ عکسی انتخاب نشده است.")
    try:
        assignment_list = json.loads(assignments)
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="ساختار انتخاب حساب‌ها نامعتبر است.")
    if not isinstance(assignment_list, list) or not assignment_list:
        raise HTTPException(status_code=400, detail="حداقل یک حساب انتخاب کن.")

    saved_images: list[str] = []
    for upload in images:
        content = await upload.read()
        if not content or len(content) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=400, detail=f"فایل {upload.filename or ''} خالی یا بزرگ‌تر از ۲۵ مگابایت است.")
        suffix = Path(upload.filename or "image.jpg").suffix or ".jpg"
        path = UPLOADS / f"{uuid.uuid4().hex}{suffix}"
        path.write_bytes(content)
        saved_images.append(str(path))

    created: list[str] = []
    used_accounts: set[str] = set()
    for assignment in assignment_list:
        account_id = str(assignment.get("account_id", ""))
        validate_account(account_id)
        if account_id in used_accounts:
            raise HTTPException(status_code=400, detail="هر حساب فقط یک‌بار در هر صف انتخاب شود.")
        used_accounts.add(account_id)
        indexes = assignment.get("image_indexes", [])
        if not isinstance(indexes, list) or not 1 <= len(indexes) <= MAX_IMAGES_PER_ACCOUNT:
            raise HTTPException(status_code=400, detail="برای هر حساب باید یک یا دو عکس انتخاب شود.")
        try:
            selected_paths = [saved_images[int(i)] for i in indexes]
        except (ValueError, IndexError, TypeError):
            raise HTTPException(status_code=400, detail="شماره یکی از عکس‌ها نامعتبر است.")
        job_id = uuid.uuid4().hex[:12]
        jobs[job_id] = {
            "id": job_id,
            "account_id": account_id,
            "account_name": f"حساب Gemini {ACCOUNT_IDS.index(account_id) + 1}",
            "image_count": len(selected_paths),
            "prompt": prompt.strip(),
            "images": selected_paths,
            "status": "QUEUED",
            "error": "",
            "result_url": "",
        }
        created.append(job_id)

    stop_event.clear()
    batch_task = asyncio.create_task(run_batch(created))
    return {"jobs": created, "queued_accounts": len(created), "mode": "SEQUENTIAL"}


@app.post("/api/stop")
async def stop() -> dict[str, str]:
    stop_event.set()
    for job in jobs.values():
        if job.get("status") not in {"COMPLETED", "FAILED", "CANCELLED", "VERIFY_IN_GEMINI"}:
            job["status"] = "CANCELLED"
    return {"status": "STOPPED"}


@app.post("/api/reset")
async def reset() -> dict[str, str]:
    if batch_task and not batch_task.done():
        raise HTTPException(status_code=409, detail="تا پایان یا توقف صف، امکان پاک‌کردن آن نیست.")
    jobs.clear()
    stop_event.clear()
    return {"status": "READY"}


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
