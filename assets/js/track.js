/* J&K 방문 통계 수집 (2026-10-04)
   ────────────────────────────────────────────────────────────────
   넥소(nexokorea.com)에서 쓰는 수집기를 옮겨 왔다. 원본: NEXO_page/assets/js/track.js

   왜 따로 만드나
     count.js 는 네이버 애널리틱스·GA 를 부르는데 등록번호가 아직 비어 있어
     지금까지 아무것도 안 세고 있었다. 이건 우리 워커로 바로 쌓이고,
     문의 원장과 같은 어드민 화면에서 본다.

   넥소에서 배운 것 하나 — **모든 공개 페이지에 실어야 한다.**
   그쪽은 한동안 일부 랜딩에 스크립트가 빠져서 32일간 그 페이지들이 0건으로 잡혔다.
   접수의 40%가 거기서 나오는데도 통계에는 없었다.

   개인정보: IP 도 쿠키도 쓰지 않는다. 브라우저가 만든 난수 하나뿐이다. */
(function () {
  var API = 'https://jnk-inquiry.chlwodud2770.workers.dev/track';

  // 어드민은 세지 않는다
  if (location.pathname.indexOf('/admin') === 0) return;

  /* 내부(우리) 방문 제외.
     주소 뒤에 ?notrack=1 이면 이 브라우저는 앞으로 안 세고, ?notrack=0 이면 다시 센다.
     어드민에 로그인하면 자동으로 켜진다. */
  try {
    var q = new URLSearchParams(location.search).get('notrack');
    if (q === '1') localStorage.setItem('jnk_notrack', '1');
    if (q === '0') localStorage.removeItem('jnk_notrack');
    if (localStorage.getItem('jnk_notrack')) return;
  } catch (e) {}

  var vid;
  try {
    vid = localStorage.getItem('jnk_vid');
    if (!vid) {
      vid = (self.crypto && crypto.randomUUID) ? crypto.randomUUID()
          : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
      localStorage.setItem('jnk_vid', vid);
    }
  } catch (e) { vid = 'anon'; }

  function send(o) {
    try {
      var body = JSON.stringify(o);
      if (navigator.sendBeacon) {
        navigator.sendBeacon(API, new Blob([body], { type: 'application/json' }));
      } else {
        fetch(API, { method: 'POST', headers: { 'content-type': 'application/json' },
                     body: body, keepalive: true });
      }
    } catch (e) {}
  }

  /* 광고 클릭인지 아닌지를 가르는 표식만 본다. 주소 전체는 보내지 않는다. */
  function adTag() {
    try {
      var q = new URLSearchParams(location.search);
      if (q.get('gclid')) return 'google-ads';
      if (q.get('fbclid')) return 'meta-ads';
      if (q.get('n_media') || q.get('n_ad_group')) return 'naver-ads';
      var u = q.get('utm_source');
      return u ? u.slice(0, 30) : '';
    } catch (e) { return ''; }
  }

  send({ t: 'pv', p: location.pathname, r: document.referrer, v: vid, ad: adTag() });

  /* 전화 클릭 — 이메일을 다 뺀 뒤로 전화가 유일한 직접 창구다.
     어느 페이지에서 전화를 누르는지가 지금 제일 중요한 숫자다. */
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    if ((a.getAttribute('href') || '').indexOf('tel:') === 0) {
      send({ t: 'tel', p: location.pathname, v: vid });
    }
  }, true);

  // 견적 폼이 실제로 접수된 경우만. site.js 가 이 사건을 쏜다.
  document.addEventListener('jnk:quote', function () {
    send({ t: 'qt', p: location.pathname, v: vid });
  });

  /* 체류 시간 — 화면을 보고 있는 동안만 센다. 탭을 묵혀 둔 시간은 빼야
     "오래 머문 페이지"가 뜻을 가진다. */
  var t0 = Date.now(), acc = 0, visible = !document.hidden, sent = 0;
  function flush() {
    var total = acc + (visible ? Date.now() - t0 : 0);
    var secs = Math.min(1800, Math.round(total / 1000));
    var delta = secs - sent;
    if (delta >= 3) { sent = secs; send({ t: 'dw', p: location.pathname, v: vid, d: delta }); }
  }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { acc += Date.now() - t0; visible = false; flush(); }
    else { t0 = Date.now(); visible = true; }
  });
  window.addEventListener('pagehide', flush);
})();
