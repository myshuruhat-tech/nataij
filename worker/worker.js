ملخص المشروع للمتابعة (المحادثة السابقة وصلت حد الاستخدام):

المشروع: تطبيقين أندرويد منفصلين + وسيط Cloudflare Worker.
- تطبيق الأم (مستودع يرفع كزيب "yt-blocker"): يتحكم بقوائم الحجب، يرسلها لوسيط Cloudflare.
- تطبيق Tozy (مستودع "nataij"، جوال الابن): واجهته الظاهرة تطبيق نتايج مباريات (ESPN)، وبالخلفية AccessibilityService يحجب يوتيوب وتطبيقات ثانية حسب قوائم تسحبها من نفس الوسيط.
- الوسيط: nataij-api.myshuruhat.workers.dev (Cloudflare Worker + KV باسم LISTS)، كود الربط pair=xeb3vuyM0FOa.

المشكلة الحالية:
تطبيق Tozy (واجهة النتايج) يعرض وقت المباراة (مثلاً 17:00) لكن ما يعرض النتيجة/النسكور حتى بعد ما تبدأ المباراة فعلياً. يعني البيانات القادمة من /fixtures في الوسيط إما ما ترجع حالة المباراة صح (state/short/hs/as) أو الواجهة (ResultsActivity.kt) ما تعرضها صح.

الملف المسؤول عن جلب النتايج: worker/worker.js (دالة fetchLeague + normalize، تجيب من ESPN API: site.api.espn.com/apis/site/v2/sports/soccer/<league>/scoreboard).
الملف المسؤول عن العرض: app/src/main/java/com/example/ytblocker/ResultsActivity.kt.

خطوات التشخيص المقترحة:
1. افتح https://nataij-api.myshuruhat.workers.dev/fixtures?date=2026-11-08&tz=Asia/Riyadh في المتصفح وشوف هل حقول hs/as/state صحيحة لمباراة بدأت فعلاً.
2. لو البيانات صحيحة من الوسيط، المشكلة في ResultsActivity.kt (كيف يقرأ/يعرض hs/as حسب state).
3. لو البيانات ناقصة/خطأ من الوسيط، المشكلة في normalize()/fetchLeague() بـ worker.js (احتمال مشكلة بمطابقة أسماء الحالات STATUS أو بتاريخ from/to).

ملاحظات مهمة:
- فيه ملف شامل بكل الكود مرفوع بالمحادثة باسم "المشروعين-الكاملين.zip" فيه README (اقرأني.md) يشرح البنية كاملة.
- ملف توقيع ثابت app/debug.keystore موجود بكل مشروع، لا تولّد واحد جديد أو التثبيت يصير يطلب حذف القديم كل مرة.
- المستخدم ولد (مو بنت)، يفضل صيغة المخاطبة المذكر.n json({ error: "no_kv" }, 500);
  const key = `activity:${pair}`;
  const noStore = { "cache-control": "no-store" };
  if (request.method === "GET") {
    const raw = await env.LISTS.get(key);
    return new Response(raw || '{"entries":[]}', { headers: { "content-type": "application/json; charset=utf-8", ...noStore } });
  }
  if (request.method === "POST") {
    let body;
    try { body = await request.json(); } catch (e) { return json({ error: "bad_json" }, 400); }
    const clean = (s, n) => (typeof s === "string" ? s.trim().slice(0, n) : "");
    const type = clean(body.type, 20);
    const kind = clean(body.kind, 10);
    const channel = clean(body.channel, 120);
    const label = clean(body.label, 200);
    if (!type || !label) return json({ error: "bad_entry" }, 400);
    const raw = await env.LISTS.get(key);
    let store = { entries: [] };
    if (raw) {
      try { store = JSON.parse(raw); if (!Array.isArray(store.entries)) store.entries = []; } catch (e) { store = { entries: [] }; }
    }
    store.entries.unshift({ ts: new Date().toISOString(), type, kind, channel, label });
    store.entries = store.entries.slice(0, MAX_ACTIVITY_ENTRIES);
    await env.LISTS.put(key, JSON.stringify(store));
    return json({ ok: true }, 200, noStore);
  }
  return json({ error: "method" }, 405);
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/blocklist") return handleBlocklist(request, env, url);
    if (url.pathname === "/usage") return handleUsage(request, env, url);
    if (url.pathname === "/searchlog") return handleSearchLog(request, env, url);
    if (url.pathname === "/activity") return handleActivity(request, env, url);
    if (request.method !== "GET") return json({ error: "method" }, 405);
    if (url.pathname !== "/fixtures") return json({ ok: true, service: "nataij" });

    const date = url.searchParams.get("date") || "";
    const tz = url.searchParams.get("tz") || "UTC";
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "bad_date" }, 400);
    if (!/^[A-Za-z_]+(\/[A-Za-z_+\-0-9]+){0,2}$/.test(tz)) return json({ error: "bad_tz" }, 400);

    let fmt;
    try {
      fmt = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" });
    } catch (e) {
      return json({ error: "bad_tz" }, 400);
    }

    // الكاش حسب (التاريخ + المنطقة) فقط، فكل المستخدمين يشاركون نفس الطلب
    const cache = caches.default;
    const freshKey = new Request(`https://cache.nataij/fresh/${date}/${tz}`);
    const staleKey = new Request(`https://cache.nataij/stale/${date}/${tz}`);

    const hit = await cache.match(freshKey);
    if (hit) return hit;

    // ESPN يقسّم الأيام بتوقيت أمريكا، فنجلب يوم قبل ويوم بعد ثم نفلتر بتاريخ منطقة المستخدم
    const base = new Date(`${date}T12:00:00Z`);
    const from = ymd(new Date(base.getTime() - 86400000));
    const to = ymd(new Date(base.getTime() + 86400000));

    const results = await Promise.allSettled(Object.keys(LEAGUES).map((s) => fetchLeague(s, from, to)));
    const failed = results.filter((r) => r.status === "rejected").length;

    if (failed === results.length) {
      const stale = await cache.match(staleKey);
      if (stale) return stale;
      return json({ error: "upstream" }, 502);
    }

    const seen = new Set();
    const matches = results
      .flatMap((r) => (r.status === "fulfilled" ? r.value : []))
      .filter((m) => fmt.format(new Date(m.ts)) === date)
      .filter((m) => {
        const k = `${m.lid}|${m.ts}|${m.home}|${m.away}`;
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
      });
    const body = { matches };

    // إذا فيه مباراة مباشرة نحدّث أسرع، وإذا بعض الدوريات فشلت نكاشي قليل عشان نعيد المحاولة
    const anyLive = matches.some((m) => m.state === "in");
    const anyPre = matches.some((m) => m.state === "pre");
    const maxAge = failed
      ? 60
      : anyLive
      ? ttl(env, "LIVE_TTL", 60)
      : anyPre
      ? ttl(env, "UPCOMING_TTL", 600)
      : ttl(env, "DONE_TTL", 3600);

    const res = json(body, 200, { "cache-control": `public, max-age=${maxAge}` });
    ctx.waitUntil(cache.put(freshKey, res.clone()));
    if (!failed) {
      ctx.waitUntil(cache.put(staleKey, json(body, 200, { "cache-control": "public, max-age=86400" })));
    }
    return res;
  },
};
