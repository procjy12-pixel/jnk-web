/* ==========================================================================
   J&K — 스크롤 구동 엔진
   의존성 없음.

   역할을 둘로 나눕니다. 하나가 죽어도 페이지가 망가지지 않게 하기 위해서입니다.

   ① 내용이 보이느냐 (리빌 · 카운트업)  → IntersectionObserver
      rAF 가 멈추는 곳(백그라운드 탭 · 헤드리스 렌더 · 숨겨진 임베드)에서도 돕니다.
      여기가 실패하면 본문이 통째로 안 보이므로, 가장 튼튼한 수단을 씁니다.

   ② 스크롤 연출 (히어로 페이드 · 패럴랙스 · 스티키 씬) → rAF + scroll 이벤트
      순수 장식입니다. 안 돌아도 ①이 끝낸 페이지는 멀쩡히 읽힙니다.
      위치는 getBoundingClientRect() 로 잽니다. window.scrollY 는 바깥 컨테이너가
      스크롤되는 환경에서 0 으로 고정되기 때문에 쓰지 않습니다.
   ========================================================================== */
(() => {
  'use strict';

  /* ══════════════════════════════════════════════════════════
     견적 문의 수신 설정

     FormSubmit — 가입도 서버도 필요 없습니다. 이 주소로 POST 하면
     RECEIVER 메일함으로 문의가 들어옵니다.

     ★ 첫 사용 전 딱 한 번:
       사이트에서 견적 문의를 아무 내용으로 한 번 보내면
       RECEIVER 메일함으로 활성화 확인 메일이 옵니다.
       그 링크를 눌러야 이후 문의가 들어오기 시작합니다.

     받는 주소를 바꾸려면 RECEIVER 만 고치면 됩니다.
     끄고 싶으면 ENDPOINT 를 빈 문자열로 두세요 — 메일 앱 열기로 되돌아갑니다.
     ══════════════════════════════════════════════════════════ */
  const RECEIVER = 'info@frameofframe.com';
  const ENDPOINT = 'https://formsubmit.co/ajax/' + RECEIVER;

  const html   = document.documentElement;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $  = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const nf = new Intl.NumberFormat('ko-KR');

  /* ── 정적 폴백 ───────────────────────────────────────────── */
  function bailOut() {
    html.classList.remove('js-anim');
    html.classList.add('fx-off');
    // 영상은 첫 프레임에서 멈춥니다. 포스터가 그대로 보입니다.
    const v = document.getElementById('heroVid');
    if (v) { try { v.pause(); v.removeAttribute('autoplay'); } catch (e) {} }
    $$('[data-rv], .drawline, .tl__i').forEach((el) => el.classList.add('is-in'));
    $$('.scene__step').forEach((s) => s.classList.add('is-on'));
    $$('[data-count]').forEach((el) => { el.textContent = nf.format(Number(el.dataset.count)); });
    pending = [];
    counters.forEach((c) => { c.run = true; });
  }

  /* ── 대상 수집 ───────────────────────────────────────────── */
  const nav     = $('#nav');
  const heroArt = $('#heroArt');
  const hero    = $('.hero');
  const scene   = $('#scene');
  const sceneBg = $('#sceneBg');
  const steps   = $$('.scene__step');
  const progs   = $$('.scene__prog span');
  const plx     = $$('[data-plx]');

  // 처리하면서 목록에서 빼기 때문에, 다 끝나면 프레임당 할 일이 0이 됩니다
  let pending  = $$('[data-rv], .drawline, .tl__i');
  let counters = $$('[data-count]').map((el) => ({ el, to: Number(el.dataset.count), run: false }));
  counters.forEach((c) => { if (Number.isFinite(c.to)) c.el.textContent = '0'; });

  // bailOut 이 pending/counters 를 건드리므로 선언이 끝난 뒤에 호출합니다
  if (reduce) bailOut();

  /* ── ① 리빌 · 카운트업 — IntersectionObserver ────────────── */
  if (!reduce && 'IntersectionObserver' in window) {
    const ioRv = new IntersectionObserver((es) => {
      es.forEach((e) => {
        if (!e.isIntersecting) return;
        e.target.classList.add('is-in');
        ioRv.unobserve(e.target);
        pending = pending.filter((el) => el !== e.target);
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });
    pending.forEach((el) => ioRv.observe(el));

    const ioCnt = new IntersectionObserver((es) => {
      es.forEach((e) => {
        if (!e.isIntersecting) return;
        ioCnt.unobserve(e.target);
        const c = counters.find((x) => x.el === e.target);
        if (c && !c.run) count(c);
      });
    }, { threshold: 0.4 });
    counters.forEach((c) => { if (Number.isFinite(c.to)) ioCnt.observe(c.el); });

    // 씬 단계 — 화면 한가운데를 지나는 표식으로 넘깁니다.
    // rAF 가 멈춘 환경에서도 2·3단계가 안 보이는 사태를 막습니다.
    const cues = $$('.scene__cues i');
    if (cues.length) {
      const ioCue = new IntersectionObserver((es) => {
        es.forEach((e) => {
          if (!e.isIntersecting) return;
          setStep(cues.indexOf(e.target));
        });
      }, { rootMargin: '-50% 0px -50% 0px', threshold: 0 });
      cues.forEach((c) => ioCue.observe(c));
    }
  }

  /* ── 고정 CTA 바 ──────────────────────────────────────────────
     기본값은 CSS 에서 '보임'입니다. 여기서는 숨기기만 합니다.
     그래야 이 스크립트가 죽어도 바는 남아 문의 경로가 사라지지 않습니다.

     숨기는 경우는 두 가지뿐입니다 — 히어로나 견적폼이 화면에 있을 때.
     같은 버튼이 화면에 이미 있는데 바까지 뜨면 가리기만 합니다.

     IntersectionObserver 를 쓰는 이유는 리빌과 같습니다: 백그라운드 탭이나
     헤드리스에서 rAF 가 멈춰도 이건 계속 돕니다. */
  const cbar = document.getElementById('cbar');
  if (cbar && 'IntersectionObserver' in window) {
    const near = new Set();
    const zones = [document.querySelector('.hero'), document.getElementById('quote')].filter(Boolean);
    if (zones.length) {
      const ioBar = new IntersectionObserver((es) => {
        es.forEach((e) => (e.isIntersecting ? near.add(e.target) : near.delete(e.target)));
        cbar.classList.toggle('is-off', near.size > 0);
      }, { threshold: 0 });
      zones.forEach((z) => ioBar.observe(z));
    }
  }

  let lastStep = -1;
  function setStep(i) {
    if (i < 0 || i === lastStep) return;
    lastStep = i;
    steps.forEach((s, n) => s.classList.toggle('is-on', n === i));
    progs.forEach((s, n) => s.classList.toggle('on', n <= i));
  }
  let lastTop  = NaN;   // 변화 감지용 — 프레임당 rect 1회만 읽고 안 움직였으면 통째로 건너뜁니다
  let frames   = 0;     // 루프가 실제로 도는지. "안 움직임"은 고장이 아닙니다 — 안 도는 게 고장입니다

  function count(c) {
    c.run = true;
    const dur = 1300, t0 = performance.now();
    const tick = (now) => {
      const p = clamp((now - t0) / dur, 0, 1);
      const k = p === 1 ? 1 : 1 - Math.pow(2, -10 * p);   // easeOutExpo
      c.el.textContent = nf.format(Math.round(c.to * k));
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }

  function frame() {
    requestAnimationFrame(frame);
    frames++;
    if (html.classList.contains('fx-off') && pending.length === 0) {
      // 정적 모드에서도 헤더 배경만은 갱신합니다
      const t = document.body.getBoundingClientRect().top;
      if (t !== lastTop) { lastTop = t; nav.classList.toggle('is-stuck', t < -24); }
      return;
    }

    const bodyTop = document.body.getBoundingClientRect().top;
    if (bodyTop === lastTop) return;          // 아무것도 안 움직였으면 끝
    lastTop = bodyTop;

    const vh = window.innerHeight || html.clientHeight;
    if (!vh) return;

    // 헤더
    nav.classList.toggle('is-stuck', bodyTop < -24);

    // 진입 리빌 — 요소 윗변이 화면 아래에서 8% 이상 올라오면 등장.
    // bottom 은 보지 않습니다. #앵커로 중간에 진입했을 때 위쪽 요소가
    // 영원히 안 보이는 상태로 남기 때문입니다.
    if (pending.length) {
      const line = vh * 0.92;
      pending = pending.filter((el) => {
        if (el.getBoundingClientRect().top < line) { el.classList.add('is-in'); return false; }
        return true;
      });
    }

    // 숫자 카운트업
    for (const c of counters) {
      if (c.run || !Number.isFinite(c.to)) continue;
      const r = c.el.getBoundingClientRect();
      if (r.top < vh * 0.86 && r.bottom > 0) count(c);
    }

    if (reduce || html.classList.contains('fx-off')) return;

    // 히어로 — 스크롤에 따라 아트가 밀려 올라가며 사라집니다
    if (hero && heroArt) {
      const p = clamp(-hero.getBoundingClientRect().top / vh, 0, 1);
      heroArt.style.transform = `translate3d(0, ${p * 16}%, 0) scale(${1 + p * 0.1})`;
      heroArt.style.opacity = String(1 - p * 0.75);
    }

    // 패럴랙스
    for (const el of plx) {
      const r = el.getBoundingClientRect();
      if (r.bottom < -240 || r.top > vh + 240) continue;
      const off = (r.top + r.height / 2 - vh / 2) / vh;      // -1 ~ 1
      el.style.transform = `translate3d(0, ${off * (Number(el.dataset.plx) || 0.08) * 100}px, 0) scale(1.12)`;
    }

    // 스티키 씬 — 진행도로 배경을 줌하고 문구를 3단계로 넘깁니다
    if (scene && steps.length) {
      const r = scene.getBoundingClientRect();
      const total = scene.offsetHeight - vh;
      if (total > 0) {
        const p = clamp(-r.top / total, 0, 1);
        if (sceneBg) sceneBg.style.transform = `translate3d(0, ${(p - 0.5) * 8}%, 0) scale(${1.08 + p * 0.14})`;
        setStep(clamp(Math.floor(p * steps.length * 0.98), 0, steps.length - 1));
      }
    }
  }

  /* 히어로 영상 — 보이지 않을 때는 돌리지 않습니다 (배터리·발열) */
  const heroVid = document.getElementById('heroVid');
  if (heroVid && !reduce) {
    const play = () => { const r = heroVid.play(); if (r && r.catch) r.catch(() => {}); };
    play();
    document.addEventListener('visibilitychange', () => (document.hidden ? heroVid.pause() : play()));
    if ('IntersectionObserver' in window) {
      new IntersectionObserver((es) => es.forEach((e) => (e.isIntersecting ? play() : heroVid.pause())),
        { threshold: 0.01 }).observe(heroVid);
    }
  }

  if (steps.length) steps[0].classList.add('is-on');
  requestAnimationFrame(frame);

  /* scroll 이벤트로도 한 번 더 두드립니다. rAF 가 멈춘 환경에서는 이쪽이,
     scroll 이 안 오는 환경에서는 rAF 가 받쳐 줍니다. */
  addEventListener('scroll', () => { lastTop = NaN; frame(); }, { passive: true });
  addEventListener('resize', () => { lastTop = NaN; frame(); }, { passive: true });

  /* 감시견 — "화면 안에 들어와 있는데도 등장하지 못한 요소"가 있을 때만 포기합니다.
     아직 스크롤을 안 한 것(첫 화면에 대상이 없음)은 고장이 아니고,
     백그라운드 탭이라 rAF 가 멈춘 것도 고장이 아닙니다. */
  function watchdog() {
    if (document.hidden || html.classList.contains('fx-off')) return;
    const vh = window.innerHeight || html.clientHeight;
    if (!vh) { bailOut(); return; }
    const onScreen = $$('[data-rv]').filter((el) => {
      const r = el.getBoundingClientRect();
      return r.top < vh * 0.92 && r.bottom > 0;
    });
    if (!onScreen.length) return;                                  // 판단할 근거가 없음
    if (onScreen.some((el) => el.classList.contains('is-in'))) return;  // 정상 동작 중
    bailOut();
  }
  setTimeout(watchdog, 2500);
  setTimeout(watchdog, 6000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) setTimeout(watchdog, 1200);
  });

  /* ── 견적 폼 ─────────────────────────────────────────────── */
  const form    = $('#quoteForm');
  const done    = $('#done');
  const doneBox = $('#doneBox');
  const errEl   = $('#formErr');
  if (!form) return;

  const val = (id) => ($(id)?.value || '').trim();
  const checked = (name) => $$(`input[name="${name}"]:checked`).map((i) => i.value);

  function shake(el) {
    if (!el || reduce) return;
    el.animate(
      [{ transform: 'translateX(0)' }, { transform: 'translateX(-7px)' },
       { transform: 'translateX(6px)' }, { transform: 'translateX(0)' }],
      { duration: 320, easing: 'ease-in-out' }
    );
  }

  function compose() {
    const L = [];
    L.push('[ J&K 견적 요청 ]');
    L.push('');
    L.push(`품목      ${checked('type').join(', ')}`);
    L.push(`수량      ${checked('qty')[0] || ''}`);
    L.push(`희망 출고  ${checked('when')[0] || '미기재'}`);
    L.push('');
    L.push(`브랜드    ${val('#fBrand') || '미정'}`);
    L.push(`담당자    ${val('#fName')}`);
    L.push(`연락처    ${val('#fTel')}`);
    L.push(`이메일    ${val('#fMail') || '-'}`);
    const memo = val('#fMemo');
    if (memo) { L.push(''); L.push('내용'); L.push(memo); }
    L.push('');
    L.push('— jnkcorp.co.kr 견적 문의 폼');
    return L.join('\n');
  }

  const smooth = () => (reduce ? 'auto' : 'smooth');

  /* 서버로 보냅니다. 실패하면 메일 앱 열기로 되돌아가므로 문의가 사라지지 않습니다. */
  async function send(body) {
    if (!ENDPOINT) return false;
    const payload = {
      _subject: `[견적문의] ${val('#fBrand') || val('#fName')} — ${checked('qty')[0]}`,
      _template: 'table',
      _captcha: 'false',
      '품목': checked('type').join(', '),
      '수량': checked('qty')[0] || '',
      '희망 출고': checked('when')[0] || '미기재',
      '브랜드': val('#fBrand') || '미정',
      '담당자': val('#fName'),
      '연락처': val('#fTel'),
      '이메일': val('#fMail') || '-',
      '내용': val('#fMemo') || '-',
      '요약': body
    };
    if (val('#fMail')) payload._replyto = val('#fMail');
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 12000);
    try {
      const res = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
        signal: ctl.signal
      });
      if (!res.ok) return false;
      // 주의: FormSubmit 은 실패해도 HTTP 200 을 돌려주고 본문에 success:"false" 를 담습니다.
      // 상태 코드로 판단하면 실패를 접수 완료로 표시하게 됩니다.
      const j = await res.json().catch(() => null);
      return !!j && String(j.success) === 'true';
    } catch {
      return false;                     // 오프라인·차단·타임아웃
    } finally {
      clearTimeout(timer);
    }
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errEl.textContent = '';

    if (!checked('type').length) {
      errEl.textContent = '품목 유형을 하나 이상 골라주세요.';
      shake($('#pickType')); $('#pickType').scrollIntoView({ block: 'center', behavior: smooth() });
      return;
    }
    if (!checked('qty').length) {
      errEl.textContent = '수량 구간을 골라주세요.';
      shake($('#pickQty')); $('#pickQty').scrollIntoView({ block: 'center', behavior: smooth() });
      return;
    }
    if (!val('#fName') || !val('#fTel')) {
      errEl.textContent = '담당자 성함과 연락처를 적어주세요.';
      shake($('#fName').closest('.two'));
      (!val('#fName') ? $('#fName') : $('#fTel')).focus();
      return;
    }

    const body = compose();
    doneBox.textContent = body;
    const subject = `[견적문의] ${val('#fBrand') || val('#fName')} — ${checked('qty')[0]}`;
    $('#doneMail').href =
      `mailto:${RECEIVER}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;

    const btn = form.querySelector('button[type="submit"]');
    const label = btn.textContent;
    btn.disabled = true;
    btn.textContent = '보내는 중…';

    const ok = await send(body);

    btn.disabled = false;
    btn.textContent = label;

    // 접수 성공/실패에 따라 안내 문구와 버튼 구성을 바꿉니다
    $('#doneTitle').textContent = ok ? '문의가 접수됐습니다' : '견적 요청서가 준비됐습니다';
    $('#doneLede').textContent  = ok
      ? '담당자가 확인하는 대로 회신드립니다. 아래는 보내신 내용입니다.'
      : '지금 전송이 되지 않았습니다. 아래 내용을 메일로 보내시거나 복사해 전달해 주세요.';
    $('#doneBadge').textContent = ok ? 'SENT' : 'READY';
    $('#doneMail').classList.toggle('btn--red', !ok);
    $('#doneMail').textContent = ok ? '메일로도 보내기' : '메일로 보내기';

    form.style.display = 'none';
    done.classList.add('is-on');
    done.scrollIntoView({ block: 'start', behavior: smooth() });
  });

  $('#doneCopy')?.addEventListener('click', async (e) => {
    const btn = e.currentTarget;
    const text = doneBox.textContent;
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // 클립보드 권한이 없는 환경(비-HTTPS 등) 대비
      const ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); } catch { /* 여기까지 실패하면 사용자가 직접 선택합니다 */ }
      ta.remove();
    }
    const old = btn.textContent;
    btn.textContent = '복사했습니다';
    setTimeout(() => { btn.textContent = old; }, 1800);
  });

  $('#doneBack')?.addEventListener('click', () => {
    done.classList.remove('is-on');
    form.style.display = '';
    form.scrollIntoView({ block: 'start', behavior: smooth() });
  });
})();
