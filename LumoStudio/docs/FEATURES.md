# نقشه‌ی پیاده‌سازی قابلیت‌ها (نسخه‌ی ۲)

قابلیت‌های ۱۲، ۱۴، ۱۵ و ۱۶ (تغییر ابعاد، چرخش، حذف فراداده، تصویر بندانگشتی) طبق درخواست پیاده نشده‌اند.
مسیر همه‌ی فایل‌ها نسبت به `app/src/main/java/com/lumo/studio/` است.

## روش کلی

- **فایل‌های واقعی** از طریق Storage Access Framework (`ops/FileOps.kt`) خوانده/نوشته می‌شوند؛ پس برای تغییر نام، انتقال و کپی، پوشه باید با «افزودن پوشه» (دسترسی خواندن+نوشتن) معرفی شود. عکس‌های تکی‌واردشده فقط خواندنی‌اند.
- هر عملیات فایلی به **Task** (`ops/Tasks.kt`) تبدیل می‌شود: کپی / انتقال / تغییر نام. Taskها *تکرارپذیرند* (اگر نتیجه از قبل وجود داشته باشد دوباره انجام نمی‌شوند) و در **Journal** روی دیسک ثبت می‌شوند.
- عملیات طولانی با `startJob` (در `StudioViewModel`) در پس‌زمینه + سرویس پیش‌زمینه اجرا می‌شوند؛ توقف/ادامه/لغو و اعلان دارند.
- هیچ فایلی بازنویسی یا حذف قطعی نمی‌شود: نام تکراری پسوند `_2` می‌گیرد، «حذف» یعنی انتقال به `_LumoTrash`.

## جدول قابلیت‌ها

| # | قابلیت | روش | فایل‌ها |
|---|---|---|---|
| ۱ | چندپوشه‌ای | `ProjectFolder` با نوع پوشه؛ پیمایش بازگشتی SAF | `viewmodel/ScanOps.kt`, `ui/screens/FolderScreens.kt` |
| ۲ | مقایسه‌ی دو پوشه | هش SHA-256 فقط برای فایل‌های هم‌حجم؛ ۵ دسته‌ی نتیجه + کپی «فقط اول» | `FileJobs.kt` (`compareFolders`) |
| ۳ | تغییر نام گروهی | `Renamer` (حذف/جایگزینی/پیشوند/پسوند/شماره‌گذاری) + پیش‌نمایش با تشخیص تداخل | `engine/Tools.kt`, `FileJobs.kt`, `LibraryScreens.kt` |
| ۴ | انتقال هوشمند | قانون: گروه/فرمت/علامت/دسته/یک پوشه؛ `moveDocument` و فال‌بک کپی+حذف | `FileJobs.kt` (`runMove`) |
| ۵ | کپی هوشمند | `copyLog` (کلید = uri+حجم+زمان) + بررسی مقصد؛ بدون بازنویسی | `FileJobs.kt` (`runCopy`) |
| ۶ | فایل تکراری | فقط هش کامل یکسان؛ نگه‌داشتن یکی، بقیه به سطل Lumo؛ انتقال نسخه‌ها به اصل | `FileJobs.kt` (`findDuplicates`) |
| ۷ | فیلتر پیشرفته | `FilterSpec` ترکیبی (AND) | `engine/Tools.kt` (`FilterEngine`) |
| ۸ | دسته‌بندی سفارشی | `Project.categories` + `Photo.categories` مستقل از گروه | `StudioViewModel.kt` |
| ۹ | علامت‌گذاری | `Mark` (مهم/بررسی/تأیید/منتخب/مشکل) | `model/Models.kt`, `LibraryScreens.kt` |
| ۱۰ | علاقه‌مندی‌ها | لیست سراسری مسیرها (بین پروژه‌ها) | `FolderScreens.kt`, `ProjectStore.kt` |
| ۱۱ | تبدیل فرمت | JPG/PNG/WebP با اصلاح جهت EXIF؛ خروجی کپی | `engine/ImageProcessor.kt`, `ProcessOps.kt` |
| ۱۳ | فشرده‌سازی | انتخاب کیفیت + مقایسه‌ی قبل/بعد با نوار کشویی | `ProcessOps.kt`, `components/Extra.kt` |
| ۱۷ | داشبورد | `Stats.dash` + حلقه‌ی پیشرفت و نمودار میله‌ای | `engine/Tools.kt`, `InfoScreens.kt` |
| ۱۸ | گزارش خطاها | `AppError` با نوع، علت و راه‌حل | `StudioViewModel.kt`, `InfoScreens.kt` |
| ۱۹ | سلامت پروژه | ۹ بررسی (خروجی‌نداشته، بدون‌گروه، تکراری، مسیر، فضا، تداخل نام…) | `BackupOps.kt` (`runHealth`) |
| ۲۰ | ذخیره‌ی خودکار | ذخیره با هر تغییر (کانال conflated) + `onStop` + ذخیره‌ی صفحه‌ی آخر + کش اثر انگشت | `StudioViewModel.kt`, `data/` |
| ۲۱ | تاریخچه | `OpEntry` با جزئیات | `InfoScreens.kt` |
| ۲۲ | بازگشت | `UndoStep` (تغییر نام/انتقال/حذف ساخته‌شده‌ها) + snapshot گروه‌بندی؛ فقط آخرین عملیات | `FileJobs.kt` (`undoEntry`) |
| ۲۳ | پردازش پس‌زمینه | coroutine + `JobService` پیش‌زمینه | `StudioViewModel.kt`, `ops/Notifier.kt` |
| ۲۴ | توقف/ادامه/لغو | `JobScope.checkpoint()` + نوار عملیات؛ نتیجه‌ی جزئی ثبت می‌شود | `components/Extra.kt` |
| ۲۵ | اعلان پایان | `Notifier.done` (+ مجوز اعلان اندروید ۱۳) | `ops/Notifier.kt` |
| ۲۶ | ZIP چندگانه | تقسیم بر اساس تعداد یا حجم | `export/ZipBuilder.kt` |
| ۲۷ | خروجی انتخابی | `ExportScope` + انتخاب گروه‌ها | `export/Exporter.kt` |
| ۲۸ | الگوهای نام‌گذاری | توکن‌های `{gi} {group} {role_fa} {n} {k} {name} {date}`؛ الگوهای ذخیره‌شده | `engine/Tools.kt` (`NameTemplate`) |
| ۲۹ | پیش‌نمایش خروجی | `ExportPlanner` بدون نوشتن فایل (درخت، تعداد، حجم، تداخل) | `export/Exporter.kt` |
| ۳۰ | اشتراک مستقیم | `Intent.ACTION_SEND(_MULTIPLE)` | `ops/Sharer.kt` |
| ۳۱ | خروجی‌های قبلی | `ExportRecord` با نسخه؛ ZIP دوباره، اشتراک، حذف | `ExportOps.kt`, `ExportScreen.kt` |
| ۳۲ | چندپروژه‌ای | هر پروژه یک پوشه (`projects/<id>/`) | `data/ProjectStore.kt`, `BackupOps.kt` |
| ۳۳ | پشتیبان کامل | ZIP: project.json + الگوها (+ خود عکس‌ها اختیاری) | `BackupOps.kt` |
| ۳۴ | انتقال به گوشی دیگر | بازیابی به‌عنوان پروژه‌ی جدید؛ وصل‌شدن دوباره‌ی فایل‌ها با نام+حجم هنگام اسکن | `BackupOps.kt`, `ScanOps.kt` |
| ۳۵ | قفل عملیات حساس | `guard()` با آستانه‌ی قابل‌تنظیم و صفحه‌ی تأیید | `StudioViewModel.kt`, `Extra.kt` |
| ۳۶ | اسکن هوشمند | فقط جدید/تغییرکرده/ناپدیدشده؛ کش اثر انگشت | `ScanOps.kt`, `data/FingerprintCache.kt` |
| ۳۷ | تنظیمات هر پروژه | `ProjectSettings` | `model/Models.kt`, `SettingsScreen.kt` |
| ۳۸ | آفلاین کامل | بدون مجوز INTERNET و بدون وابستگی شبکه | `AndroidManifest.xml` |
| ۳۹ | مدیریت فضا | جدول مصرف + فایل‌های حجیم با پیش‌نمایش و انتقال به سطل | `InfoScreens.kt` |
| ۴۰ | بازیابی اضطراری | Journal؛ پس از بستن ناگهانی فقط باقی‌مانده انجام می‌شود | `ops/Tasks.kt`, `FileJobs.kt` |

## نکته‌ها

- برای تغییر نام/انتقال/حذف تکراری، پوشه را از «ابزارها ← پوشه‌ها» اضافه کن.
- بعد از حذف تکراری‌ها یا تغییر نام، آخرین عملیات از «تاریخچه ← بازگشت» قابل برگشت است.
- این نسخه در محیط ساخت من کامپایل نشد (Gradle/Kotlin در دسترس نبود). اولین Build در GitHub Actions اگر خطای کامپایل داد، متن خطا را بفرست تا همان لحظه رفعش کنم.
