/* ==========================================================================
   J&K — 방문 계측

   ★ 고칠 곳은 아래 상자 안 두 줄뿐입니다. 22개 페이지가 이 파일 하나를 봅니다.

   왜 이렇게 하나 — 페이지마다 코드를 심어 두면 나중에 번호를 바꿀 때
   22곳을 열어야 하고, 한 곳만 빠뜨려도 그 페이지 방문이 통째로 안 잡힙니다.
   여기 한 곳만 두면 그럴 일이 없습니다.

   무엇을 세나
     · 페이지 방문 (어느 페이지를 몇 명이 봤나)
     · 전화 걸기 · 메일 쓰기 클릭
     · 견적 문의 전송 성공  ← 실제로 중요한 건 이 숫자입니다

   무엇을 세지 않나
     · /admin/ 방문 — 우리가 우리 대시보드를 본 것까지 세면 숫자가 더러워집니다
     · 내 방문 — /admin/ 에서 '내 방문 빼기' 를 켠 브라우저
     · 로컬·미리보기 — jnkcorp.co.kr 이 아닌 주소에서 열었을 때
   ========================================================================== */
(() => {
  'use strict';

  /* ══════════════════════════════════════════════════════════════════
     ┃ 여 기 만  고 치 세 요
     ┃
     ┃ NAVER — 네이버 애널리틱스 등록번호
     ┃         analytics.naver.com → 사이트 등록 후 받는 값입니다.
     ┃         네이버로 들어온 손님이 어떤 검색어로 왔는지 여기서 봅니다.
     ┃
     ┃ GA4   — 구글 애널리틱스 측정 ID. G- 로 시작합니다.
     ┃         analytics.google.com → 관리 → 데이터 스트림.
     ┃
     ┃ 둘 중 하나만 채워도 그것만 돌아갑니다. 비워 두면 아무것도 안 보냅니다.
     ══════════════════════════════════════════════════════════════════ */
  const NAVER = '';
  const GA4   = '';
  /* ════════════════════════════════════════════════════════════════ */

  const LIVE_HOST = /(^|\.)jnkcorp\.co\.kr$/i;
  const OPT_OUT   = 'jnk_noc';

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); return true; } catch { return false; } },
    del(k) { try { localStorage.removeItem(k); return true; } catch { return false; } },
  };

  /* /admin/ 이 이 객체를 읽어 설치 상태를 판정합니다.
     "붙였다고 하는데 정말 도는가" 를 추측이 아니라 사실로 답하려고 둡니다. */
  const state = window.JNK_COUNT = {
    naver: NAVER || null,
    ga4: GA4 || null,
    configured: !!(NAVER || GA4),
    skipped: null,     // 안 보낸 이유
    sent: [],          // 실제로 나간 것
    goals: [],         // 이 화면에서 일어난 목표 달성
    goal: () => {},    // 아래에서 채웁니다
    optOut: {
      on: () => (store.set(OPT_OUT, '1'), true),
      off: () => (store.del(OPT_OUT), true),
      is: () => store.get(OPT_OUT) === '1',
    },
  };

  const skip =
      !LIVE_HOST.test(location.hostname)        ? 'preview'
    : location.pathname.indexOf('/admin/') === 0 ? 'admin'
    : state.optOut.is()                          ? 'optout'
    : !state.configured                          ? 'unconfigured'
    : null;

  state.skipped = skip;
  if (skip) return;

  /* ── 네이버 애널리틱스 ──────────────────────────────────────────
     wcslog.js 를 불러온 뒤 wcs_do() 를 부르면 방문 한 건이 잡힙니다.
     onload 안에서 부르는 게 핵심입니다. 밖에서 부르면 wcs 가 아직 없습니다. */
  if (NAVER) {
    const s = document.createElement('script');
    s.src = 'https://wcs.naver.net/wcslog.js';
    s.async = true;
    s.onload = () => {
      try {
        window.wcs_add = window.wcs_add || {};
        window.wcs_add.wa = NAVER;
        if (window.wcs) { window.wcs_do(); state.sent.push('naver'); }
      } catch { /* 계측이 실패해도 페이지는 멀쩡해야 합니다 */ }
    };
    document.head.appendChild(s);
  }

  /* ── GA4 ──────────────────────────────────────────────────────── */
  if (GA4) {
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', GA4, { anonymize_ip: true });
    const g = document.createElement('script');
    g.src = 'https://www.googletagmanager.com/gtag/js?id=' + encodeURIComponent(GA4);
    g.async = true;
    document.head.appendChild(g);
    state.sent.push('ga4');
  }

  /* ── 목표 달성 ─────────────────────────────────────────────────
     방문 수보다 이게 중요합니다. 몇 명이 왔는지가 아니라
     몇 명이 연락을 했는지가 매출에 붙는 숫자니까요. */
  const goal = (name, detail) => {
    state.goals.push(name);
    try {
      if (GA4 && window.gtag) window.gtag('event', name, detail || {});

      // 네이버 전환은 '실제로 문의가 들어온 것' 에만 올립니다.
      //
      // ⚠️ wcs_do() 를 인자 없이 부르면 그건 전환이 아니라 **방문 한 건**이
      //    또 잡힙니다. 전화 링크를 세 번 누른 사람이 방문 네 명이 되는 겁니다.
      //    그래서 cnv 가 없으면 네이버에는 아무것도 보내지 않습니다.
      //
      // 전화·메일 클릭은 '연락하려다 만 것' 일 수도 있어 전환으로 치지 않습니다.
      // 그쪽은 GA4 이벤트로만 남깁니다.
      if (name === 'quote' && NAVER && window.wcs && window.wcs.cnv) {
        window.wcs_do(window.wcs.cnv('2', '1'));   // 2 = 신청·문의
      }
    } catch { /* 계측이 실패해도 페이지는 멀쩡해야 합니다 */ }
  };
  state.goal = goal;

  /* 전화·메일은 어느 페이지에서든 잡힙니다. 한 곳에서 처리해 둡니다. */
  document.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('a[href^="tel:"], a[href^="mailto:"]');
    if (!a) return;
    goal(a.getAttribute('href').indexOf('tel:') === 0 ? 'call' : 'mail',
         { page: location.pathname });
  }, true);

  /* 견적 폼이 전송에 성공하면 site.js 가 이 이벤트를 쏩니다. */
  document.addEventListener('jnk:quote', () => goal('quote', { page: location.pathname }));
})();
