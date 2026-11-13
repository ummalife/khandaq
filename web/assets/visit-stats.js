/* khandaq.org: visit statistics with Yandex Metrica and Google Analytics, and only with the
   visitor's consent (privacy policy, section "This website").

   - Until there is a choice, a notice at the bottom of the page asks for one, and nothing is loaded
     from Yandex or Google. "Decline" keeps it that way.
   - "Accept" loads both counters, on this visit and on later ones.
   - Declining after accepting stops them, deletes the cookies and storage they left on this site
     and reloads the page without them. So does a page that still runs them after a decline in
     another tab, or that the back/forward cache brings back after one.
   - The button marked data-visit-stats-open in the privacy policy brings the notice back; Escape puts
     the notice away without an answer.
   - The choice is kept in localStorage under "kq-visit-stats". Webvisor, the click map, Google
     signals and ad personalisation stay off. Without scripts nothing loads at all, crawlers included.

   On a phone the notice asks in one short question, and the home page keeps its download button in a
   bar under the thumb (site.js). The notice rides above that bar while it is out, so the button is
   never covered, and steps aside with it while the download list is in view. */
(function () {
  'use strict';

  var METRIKA_ID = 108575437;
  var METRIKA_TAG = 'https://mc.yandex.ru/metrika/tag.js?id=' + METRIKA_ID;
  var GA_ID = 'G-XQZD21QBB2';
  var GA_TAG = 'https://www.googletagmanager.com/gtag/js?id=' + GA_ID;
  var KEY = 'kq-visit-stats';
  var GRANTED = 'granted';
  var DENIED = 'denied';

  /* The same rule as site.js: the page's own language, English otherwise. The brief question is what
     a phone shows (style.css): two lines at most from 320 px up, in both languages. */
  var TEXT = {
    en: {
      label: 'Visit statistics',
      before: 'We count visits to this website with Yandex Metrica and Google Analytics, but only if you ' +
        'allow it. This does not concern the app. Details are in the ',
      link: 'privacy policy',
      after: '.',
      brief: 'Allow Yandex Metrica and Google Analytics on this site? ',
      briefLink: 'Details',
      href: '/privacy.html#website',
      accept: 'Accept',
      decline: 'Decline'
    },
    ru: {
      label: 'Статистика посещений',
      before: 'Посещения этого сайта мы считаем Яндекс\u00a0Метрикой и\u00a0Google Analytics, но\u00a0только ' +
        'если вы разрешите. Приложения это не\u00a0касается. Подробнее в\u00a0',
      link: 'политике конфиденциальности',
      after: '.',
      brief: 'Разрешить Яндекс\u00a0Метрику и\u00a0Google Analytics на\u00a0этом сайте? ',
      briefLink: 'Подробнее',
      href: '/ru/privacy.html#website',
      accept: 'Принять',
      decline: 'Отклонить'
    }
  };

  function read() {
    try {
      return window.localStorage.getItem(KEY);
    } catch (e) {
      return null;
    }
  }

  function write(value) {
    try {
      window.localStorage.setItem(KEY, value);
    } catch (e) {
      /* Storage is off (a private window): the choice holds for this page, and the notice asks again
         on the next one. */
    }
  }

  function inject(src) {
    var script = document.createElement('script');
    script.async = true;
    script.src = src;
    document.head.appendChild(script);
  }

  /* The options the counter's own code gives, without e-commerce (the site has no shop, and Metrica
     would otherwise read Google's dataLayer as one), and with Webvisor and the click map off in so many
     words: an option left out here is taken from the counter's settings in Metrica, so a switch turned
     on there would start recording without a change to this file. */
  function loadMetrika() {
    if (window.ym) {
      return;
    }
    var ym = function () {
      (ym.a = ym.a || []).push(arguments);
    };
    ym.l = Date.now();
    window.ym = ym;
    inject(METRIKA_TAG);
    ym(METRIKA_ID, 'init', {
      ssr: true,
      webvisor: false,
      clickmap: false,
      referrer: document.referrer,
      url: window.location.href,
      accurateTrackBounce: true,
      trackLinks: true
    });
  }

  function loadGoogle() {
    if (window.gtag) {
      return;
    }
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () {
      window.dataLayer.push(arguments);
    };
    /* Statistics only: advertising storage, user data and personalisation stay denied for Google.
       The default has to come before the config, or the first hit goes out without it. */
    window.gtag('consent', 'default', {
      ad_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied',
      analytics_storage: 'granted'
    });
    window.gtag('js', new Date());
    window.gtag('config', GA_ID, { allow_google_signals: false, allow_ad_personalization_signals: false });
    inject(GA_TAG);
  }

  function load() {
    window['disableYaCounter' + METRIKA_ID] = false;
    window['ga-disable-' + GA_ID] = false;
    loadMetrika();
    loadGoogle();
  }

  /* Metrica's cookies start with _ym (mdd is its own as well); Google's are _ga, _ga_<stream> and,
     from older or advertising tags that this site does not run, _gid, _gat and _gcl. */
  function isCounterCookie(name) {
    return name.indexOf('_ym') === 0 || name === 'mdd' || name === '_ga' || name.indexOf('_ga_') === 0 ||
      name === '_gid' || name.indexOf('_gat') === 0 || name.indexOf('_gcl') === 0;
  }

  function isCounterKey(key) {
    return key.indexOf('_ym') === 0 || key.indexOf('_gcl') === 0;
  }

  function clearStorage(storage) {
    for (var i = storage.length - 1; i >= 0; i--) {
      var key = storage.key(i);
      if (key && isCounterKey(key)) {
        storage.removeItem(key);
      }
    }
  }

  /* Stops both counters on this page and removes what they keep on this site: their cookies, set on
     the host or on the whole domain, and their keys in local and session storage. The site's own keys
     (the theme and this choice) are left alone. */
  function forget() {
    window['disableYaCounter' + METRIKA_ID] = true;
    window['ga-disable-' + GA_ID] = true;
    var host = window.location.hostname;
    var domains = ['', host, '.' + host.replace(/^www\./, '')];
    var pairs = document.cookie ? document.cookie.split(';') : [];
    for (var i = 0; i < pairs.length; i++) {
      var name = pairs[i].split('=')[0].trim();
      if (!isCounterCookie(name)) {
        continue;
      }
      for (var j = 0; j < domains.length; j++) {
        document.cookie = name + '=; Max-Age=0; path=/' + (domains[j] ? '; domain=' + domains[j] : '');
      }
    }
    try {
      clearStorage(window.localStorage);
      clearStorage(window.sessionStorage);
    } catch (e) {
      /* Storage is off: the counters could keep nothing in it either. */
    }
  }

  function button(label) {
    var node = document.createElement('button');
    node.type = 'button';
    node.className = 'btn btn-plain';
    node.textContent = label;
    return node;
  }

  /* A line of the notice: text, the link to the policy's section, text. */
  function sentence(className, before, label, after, href) {
    var line = document.createElement('p');
    line.className = className;
    var link = document.createElement('a');
    link.href = href;
    link.textContent = label;
    line.appendChild(document.createTextNode(before));
    line.appendChild(link);
    line.appendChild(document.createTextNode(after));
    return line;
  }

  /* Accept and Decline look the same on purpose: neither answer is the expected one. The notice
     carries both wordings, and style.css shows one: the whole sentence on a wide window, the brief
     question on a phone, where the notice has to leave the page in view. */
  function build(text) {
    var notice = document.createElement('section');
    notice.className = 'consent';
    notice.setAttribute('aria-label', text.label);
    notice.hidden = true;

    var row = document.createElement('div');
    row.className = 'wrap consent-row';

    var actions = document.createElement('div');
    actions.className = 'consent-actions';
    var accept = button(text.accept);
    var decline = button(text.decline);
    actions.appendChild(accept);
    actions.appendChild(decline);

    row.appendChild(sentence('consent-text consent-full', text.before, text.link, text.after, text.href));
    row.appendChild(sentence('consent-text consent-brief', text.brief, text.briefLink, '', text.href));
    row.appendChild(actions);
    notice.appendChild(row);
    document.body.appendChild(notice);
    return { notice: notice, accept: accept, decline: decline };
  }

  function start() {
    var text = TEXT[document.documentElement.lang === 'ru' ? 'ru' : 'en'];
    var ui = build(text);
    var notice = ui.notice;
    var root = document.documentElement;
    var bar = document.querySelector('[data-thumb-bar]');
    var opener = null;
    var before = null;

    /* The notice sits over the bottom of the page, and the page grows by its height, so the end of
       the page (the support address in the footer) stays within reach. Where the download bar under
       the thumb is out (on a phone, site.js), the notice rides above it. While the bar steps aside for
       the download list, the notice goes with it, so the list's own buttons are not covered either,
       unless the keyboard is in the notice. */
    function fit() {
      if (notice.hidden) {
        document.body.style.paddingBottom = '';
        root.style.scrollPaddingBottom = '';
        return;
      }
      var shown = Boolean(bar) && window.getComputedStyle(bar).display !== 'none';
      var barTucked = shown && bar.classList.contains('is-tucked');
      var tucked = barTucked && !notice.contains(document.activeElement);
      var lift = shown && !barTucked ? bar.offsetHeight : 0;
      notice.classList.toggle('is-lifted', lift > 0);
      notice.classList.toggle('is-tucked', tucked);
      notice.inert = tucked;
      notice.style.transform = lift ? 'translateY(-' + lift + 'px)' : '';
      document.body.style.paddingBottom = notice.offsetHeight + 'px';
      /* Keyboard focus scrolls into view above the notice and the bar, not under them (WCAG 2.2,
         2.4.11). */
      root.style.scrollPaddingBottom = tucked ? '' : notice.offsetHeight + lift + 'px';
    }

    function show(from) {
      opener = from || null;
      notice.hidden = false;
      fit();
      if (opener) {
        ui.accept.focus();
      }
    }

    function hide() {
      var back = notice.contains(document.activeElement) ? before : null;
      notice.hidden = true;
      fit();
      if (opener) {
        opener.focus();
      } else if (back && root.contains(back)) {
        /* After the first answer the keyboard goes back to where it was before it came to the notice,
           not to the start of the document; the page does not move. */
        back.focus({ preventScroll: true });
      }
      opener = null;
    }

    ui.accept.addEventListener('click', function () {
      write(GRANTED);
      hide();
      load();
    });

    ui.decline.addEventListener('click', function () {
      var withdrawn = read() === GRANTED || Boolean(window.ym) || Boolean(window.gtag);
      write(DENIED);
      hide();
      if (withdrawn) {
        forget();
        window.location.reload();
      }
    });

    /* Escape puts the notice away without an answer: a choice made earlier stays as it is, and with
       none the notice asks again on the next page. */
    notice.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' || event.key === 'Esc') {
        hide();
      }
    });

    /* The last place the keyboard was outside the notice. */
    document.addEventListener('focusin', function (event) {
      if (!notice.contains(event.target)) {
        before = event.target;
      }
    });

    /* A notice that stayed in view for the keyboard steps aside once the keyboard leaves it. */
    notice.addEventListener('focusout', function () {
      window.setTimeout(fit, 0);
    });

    /* The privacy policy's button stays hidden without scripts, where there is nothing to choose. */
    var openers = document.querySelectorAll('[data-visit-stats-open]');
    for (var i = 0; i < openers.length; i++) {
      openers[i].hidden = false;
      openers[i].addEventListener('click', function (event) {
        show(event.currentTarget);
      });
    }

    /* The notice's height follows the width of the window and the wrapping of its text; the bar
       comes and goes with the download list and with the width of the window. */
    if (window.ResizeObserver) {
      var sizes = new window.ResizeObserver(fit);
      sizes.observe(notice);
      if (bar) {
        sizes.observe(bar);
      }
    } else {
      window.addEventListener('resize', fit);
    }
    if (bar && window.MutationObserver) {
      new window.MutationObserver(fit).observe(bar, { attributes: true, attributeFilter: ['class'] });
    }

    /* A page that still runs the counters after the visitor declined in another tab, or that the
       back/forward cache brings back after a decline, reloads without them. */
    function recheck() {
      if (read() !== GRANTED && (window.ym || window.gtag)) {
        forget();
        window.location.reload();
      }
    }
    window.addEventListener('storage', function (event) {
      if (event.key === KEY || event.key === null) {
        recheck();
      }
    });
    window.addEventListener('pageshow', function (event) {
      if (event.persisted) {
        recheck();
      }
    });

    var choice = read();
    if (choice === GRANTED) {
      load();
    } else if (choice === DENIED) {
      /* A counter may still write while the page unloads after a decline: that goes on the next page. */
      forget();
    } else {
      show(null);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
