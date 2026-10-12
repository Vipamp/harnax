// Three behaviours only: mobile nav, sticky TOC highlight, reveal-on-scroll.
(() => {
  const html = document.documentElement;
  html.classList.add('js');

  const toggle = document.querySelector('[data-nav-toggle]');
  const nav = document.querySelector('.hdr-nav');
  if (toggle && nav) {
    toggle.addEventListener('click', () => {
      const open = nav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', String(open));
    });
  }

  const tocLinks = [...document.querySelectorAll('.toc a')];
  const byId = new Map(tocLinks.map((a) => [a.getAttribute('href').slice(1), a]));
  if (byId.size) {
    const mark = (link) => {
      tocLinks.forEach((a) => a.removeAttribute('aria-current'));
      link.setAttribute('aria-current', 'true');
    };
    const initial = byId.values().next().value;
    if (initial) mark(initial);
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        const link = byId.get(e.target.id);
        if (link && e.isIntersecting) mark(link);
      });
    }, { rootMargin: '-96px 0px -55% 0px' });
    document.querySelectorAll('[data-band]').forEach((s) => io.observe(s));
  }

  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const items = [...document.querySelectorAll('[data-reveal]')];
  if (reduce || !('IntersectionObserver' in window)) {
    items.forEach((el) => el.classList.add('in'));
  } else {
    const ro = new IntersectionObserver((entries, obs) => {
      entries.forEach((e) => {
        if (e.isIntersecting) { e.target.classList.add('in'); obs.unobserve(e.target); }
      });
    }, { threshold: 0.12 });
    items.forEach((el) => ro.observe(el));
  }
})();
