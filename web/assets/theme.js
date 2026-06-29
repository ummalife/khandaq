// Khandaq site theme toggle. Default follows the OS (prefers-color-scheme via CSS);
// this lets visitors override and remembers the choice.
(function () {
  var d = document.documentElement;
  var btn = document.getElementById('theme-toggle');
  function current() {
    return d.getAttribute('data-theme') ||
      (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  }
  function paint() {
    if (!btn) return;
    var dark = current() === 'dark';
    btn.textContent = dark ? '☀' : '☾'; // ☀ when dark (switch to light) / ☾ when light
    btn.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  }
  paint();
  if (btn) {
    btn.addEventListener('click', function () {
      var next = current() === 'dark' ? 'light' : 'dark';
      d.setAttribute('data-theme', next);
      try { localStorage.setItem('kq-theme', next); } catch (e) {}
      paint();
    });
  }
})();
