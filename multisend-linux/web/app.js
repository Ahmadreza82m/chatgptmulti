const images = document.querySelector('#images');
const preview = document.querySelector('#preview');
const jobsEl = document.querySelector('#jobs');
const count = document.querySelector('#count');
const start = document.querySelector('#start');
const stop = document.querySelector('#stop');
const reset = document.querySelector('#reset');

images.addEventListener('change', () => {
  preview.innerHTML = '';
  [...images.files].forEach(file => {
    const img = document.createElement('img');
    img.src = URL.createObjectURL(file);
    preview.appendChild(img);
  });
});

async function refresh() {
  const res = await fetch('/api/jobs');
  const jobs = await res.json();
  count.textContent = jobs.length;
  jobsEl.innerHTML = jobs.map(j => {
    const cls = j.status === 'COMPLETED' ? 'ok' : j.status === 'FAILED' || j.status === 'CANCELLED' ? 'bad' : '';
    return `<div class="job"><div class="jobline"><b>تصویر ${j.index + 1}</b><span class="status ${cls}">${j.status}</span></div><small>Session: ${j.session}</small>${j.error ? `<small class="bad">${j.error}</small>` : ''}</div>`;
  }).join('');
}

start.addEventListener('click', async () => {
  if (!images.files.length) return alert('حداقل یک تصویر انتخاب کنید.');
  const prompt = document.querySelector('#prompt').value.trim();
  const sessions = document.querySelector('#sessions').value.trim();
  if (!prompt) return alert('پرامپت را وارد کنید.');
  if (!sessions) return alert('Sessionها را وارد کنید.');
  const form = new FormData();
  form.append('prompt', prompt);
  form.append('sessions', sessions);
  [...images.files].forEach(file => form.append('images', file, file.name));
  start.disabled = true;
  try {
    const res = await fetch('/api/batch', {method:'POST', body:form});
    if (!res.ok) throw new Error(await res.text());
  } catch (e) { alert('شروع ناموفق بود: ' + e.message); }
  start.disabled = false;
  refresh();
});

stop.addEventListener('click', async () => {
  await fetch('/api/stop', {method:'POST'});
  refresh();
});

reset.addEventListener('click', async () => {
  await fetch('/api/reset', {method:'POST'});
  refresh();
});

refresh();
setInterval(refresh, 1500);
