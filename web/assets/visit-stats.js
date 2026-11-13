/* khandaq.org: visit statistics with Yandex Metrica and Google Analytics, and only with the
   visitor's consent (privacy policy, section "This website").

   - Until there is a choice, a notice at the bottom of the page asks for one, and nothing is loaded
     from Yandex or Google. "Decline" keeps it that way.
   - "Accept" loads both counters, on this visit and on later ones.
   - Declining after accepting stops them, deletes the cookies and storage they left on this site
     and reloads the page without them.
   - The button marked data-visit-stats-open in the privacy policy brings the notice back.
   - The choice is kept in localStorage under "kq-visit-stats". Webvisor, Google signals and ad
     personalisation stay off. Without scripts nothing loads at all, crawlers included.

   On a phone the home page keeps its download button in a bar under the thumb (site.js). The notice
   rides above that bar while it is out, so the button is never covered. */
(function () {
  'use strict';

  var METRIKA_ID = 108575437;
  var METRIKA_TAG = 'https://mc.yandex.ru/metrika/tag.js?id=' + METRIKA_ID;
  var GA_ID = 'G-XQZD21QBB2';
  var GA_TAG = 'https://www.googletagmanager.com/gtag/js?id=' + GA_ID;
  var KEY = 'kq-visit-stats';
  var GRANTED = 'granted';
  var DENIED = 'denied';

  /* The same rule as site.js: the page's own language, English otherwise. */
  var TEXT = {
    en: {
      label: 'Visit statistics',
      before: 'We count visits to this website with Yandex Metrica and Google Analytics, but only if you ' +
        'allow it. This does not concern the app. Details are in the ',
      link: 'privacy policy',
      after: '.',
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

  /* The options the counter's own code gives, without Webvisor and without e-commerce: the site has
     no shop, and Metrica would otherwise read Google's dataLayer as one. */
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
      clickmap: true,
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

  /* Accept and Decline look the same on purpose: neither answer is the expected one. */
  function build(text) {
    var notice = document.createElement('section');
    notice.className = 'consent';
    notice.setAttribute('aria-label', text.label);
    notice.hidden = true;

    var row = document.createElement('div');
    row.className = 'wrap consent-row';

    var line = document.createElement('p');
    line.className = 'consent-text';
    var link = document.createElement('a');
    link.href = text.href;
    link.textContent = text.link;
    line.appendChild(document.createTextNode(text.before));
    line.appendChild(link);
    line.appendChild(document.createTextNode(text.after));

    var actions = document.createElement('div');
    actions.className = 'consent-actions';
    var accept = button(text.accept);
    var decline = button(text.decline);
    actions.appendChild(accept);
    actions.appendChild(decline);

    row.appendChild(line);
    row.appendChild(actions);
    notice.appendChild(row);
    document.body.appendChild(notice);
    return { notice: notice, accept: accept, decline: decline };
  }

  function start() {
    var text = TEXT[document.documentElement.lang === 'ru' ? 'ru' : 'en'];
    var ui = build(text);
    var notice = ui.notice;
    var bar = document.querySelector('[data-thumb-bar]');
    var opener = null;

    /* The download bar under the thumb, while it is out: on a phone, and not tucked away while the
       download list is in view. */
    function barHeight() {
      if (!bar || bar.classList.contains('is-tucked') || window.getComputedStyle(bar).display === 'none') {
        return 0;
      }
      return bar.offsetHeight;
    }

    /* The notice sits over the bottom of the page, above that bar when there is one, and the page
       grows by its height, so the end of the page (the support address in the footer) stays within
       reach. */
    function fit() {
      if (notice.hidden) {
        document.body.style.paddingBottom = '';
        return;
      }
      var lift = barHeight();
      notice.classList.toggle('is-lifted', lift > 0);
      notice.style.transform = lift ? 'translateY(-' + lift + 'px)' : '';
      document.body.style.paddingBottom = notice.offsetHeight + 'px';
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
      notice.hidden = true;
      fit();
      if (opener) {
        opener.focus();
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
