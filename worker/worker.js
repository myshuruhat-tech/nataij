// Cloudflare Worker: وسيط بين تطبيق نتايج و API-Football
// المفتاح محفوظ كسر باسم API_FOOTBALL_KEY (Settings → Variables and Secrets) ولا يظهر في التطبيق ولا في GitHub.
//
// الطلب:  GET /fixtures?date=YYYY-MM-DD&tz=Africa/Mogadishu
// الرد:   { matches: [ {lid, ts, state, short, elapsed, home, away, hs, as, hl, al} ] }

const LEAGUE_IDS = [307, 2, 39, 140, 135, 78, 61]; // السعودي، أبطال أوروبا، إنجليزي، إسباني، إيطالي، ألماني، فرنسي

const LIVE = new Set(["1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT", "SUSP"]);
const DONE = new Set(["FT", "AET", "PEN", "AWD", "WO"]);

// مدة الكاش بالثواني (تقدر تغيّرها من Variables بدون تعديل الكود)
const ttl = (env, name, def) => Number(env[name]) || def;

const json = (obj, status = 200, extra = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extra },
  });

function normalize(item) {
  const short = item.fixture?.status?.short || "NS";
  const state = LIVE.has(short) ? "in" : DONE.has(short) ? "post" : "pre";
  return {
    lid: item.league?.id,
    ts: (item.fixture?.timestamp || 0) * 1000,
    state,
    short,
    elapsed: item.fixture?.status?.elapsed ?? null,
    home: item.teams?.home?.name || "—",
    away: item.teams?.away?.name || "—",
    hs: item.goals?.home ?? null,
    as: item.goals?.away ?? null,
    hl: item.teams?.home?.logo || null,
    al: item.teams?.away?.logo || null,
  };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method !== "GET") return json({ error: "method" }, 405);
    if (url.pathname !== "/fixtures") return json({ ok: true, service: "nataij" });

    const date = url.searchParams.get("date") || "";
    const tz = url.searchParams.get("tz") || "UTC";
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "bad_date" }, 400);
    if (!/^[A-Za-z_]+(\/[A-Za-z_+\-0-9]+){0,2}$/.test(tz)) return json({ error: "bad_tz" }, 400);
    if (!env.API_FOOTBALL_KEY) return json({ error: "no_key" }, 500);

    // الكاش حسب (التاريخ + المنطقة) فقط، فكل المستخدمين يشاركون نفس الطلب
    const cache = caches.default;
    const freshKey = new Request(`https://cache.nataij/fresh/${date}/${tz}`);
    const staleKey = new Request(`https://cache.nataij/stale/${date}/${tz}`);

    const hit = await cache.match(freshKey);
    if (hit) return hit;

    let body;
    try {
      const api = `https://v3.football.api-sports.io/fixtures?date=${date}&timezone=${encodeURIComponent(tz)}`;
      const r = await fetch(api, { headers: { "x-apisports-key": env.API_FOOTBALL_KEY } });
      const data = await r.json();

      const errs = data.errors && !Array.isArray(data.errors) ? Object.values(data.errors) : [];
      if (!r.ok || errs.length) {
        const msg = String(errs[0] || `http ${r.status}`);
        const stale = await cache.match(staleKey);
        if (stale) return stale;
        const code = /free plans/i.test(msg) ? "plan_date" : /limit|request/i.test(msg) ? "quota" : "upstream";
        return json({ error: code, detail: msg }, code === "plan_date" ? 403 : 502);
      }

      const matches = (data.response || [])
        .filter((it) => LEAGUE_IDS.includes(it.league?.id))
        .map(normalize);
      body = { matches };
    } catch (e) {
      const stale = await cache.match(staleKey);
      if (stale) return stale;
      return json({ error: "upstream" }, 502);
    }

    // إذا فيه مباراة مباشرة نحدّث أسرع، وغير كذا نكاشي أطول عشان نوفّر الـ 100 طلب
    const anyLive = body.matches.some((m) => m.state === "in");
    const anyPre = body.matches.some((m) => m.state === "pre");
    const maxAge = anyLive
      ? ttl(env, "LIVE_TTL", 300)
      : anyPre
      ? ttl(env, "UPCOMING_TTL", 900)
      : ttl(env, "DONE_TTL", 3600);

    const res = json(body, 200, { "cache-control": `public, max-age=${maxAge}` });
    ctx.waitUntil(cache.put(freshKey, res.clone()));
    ctx.waitUntil(
      cache.put(staleKey, json(body, 200, { "cache-control": "public, max-age=86400" }))
    );
    return res;
  },
};
