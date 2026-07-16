/**
 * Accesibilidad del manual Lazaro AI.
 * - Mueve el foco real al destino de los enlaces de salto.
 * - Anuncia en vivo cambios menores si hiciera falta (reservado).
 */
(function () {
  function focusTarget(hash) {
    if (!hash || hash === "#") return;
    var id = hash.slice(1);
    var el = document.getElementById(id);
    if (!el) return;
    if (!el.hasAttribute("tabindex")) {
      el.setAttribute("tabindex", "-1");
    }
    el.focus({ preventScroll: false });
  }

  document.querySelectorAll('a[href^="#"]').forEach(function (link) {
    link.addEventListener("click", function () {
      var hash = link.getAttribute("href");
      // Dejar que el navegador salte; enfocar en el siguiente tick.
      window.setTimeout(function () {
        focusTarget(hash);
      }, 0);
    });
  });

  if (location.hash) {
    window.setTimeout(function () {
      focusTarget(location.hash);
    }, 0);
  }
})();
