/* Haven translation contribution page.
 * Browses docs/i18n/strings.json and hands off edits to GitHub's own
 * editor / fork / PR flow (GitHub does the auth). No backend, no secrets.
 * Vanilla JS — the Pages site has no bundler. */
(function () {
  "use strict";

  var REPO = "GlassHaven/Haven", BRANCH = "main", RENDER_CAP = 800;

  var LOCALE_NAMES = {
    ar: "Arabic · العربية", bn: "Bengali · বাংলা", de: "German · Deutsch",
    es: "Spanish · Español", fr: "French · Français", hi: "Hindi · हिन्दी",
    ja: "Japanese · 日本語", ko: "Korean · 한국어", pt: "Portuguese · Português",
    ru: "Russian · Русский", sr: "Serbian · српски",
    zh: "Chinese (Simplified) · 简体中文"
  };

  var data = null;          // the fetched JSON
  var total = 0;            // total source strings
  var index = {};           // module -> { name -> stringObj }
  var edits = {};           // code -> { module -> { name -> value } }

  // ---------- tiny DOM helper ----------
  function el(tag, props, kids) {
    var n = document.createElement(tag);
    if (props) for (var k in props) {
      if (k === "class") n.className = props[k];
      else if (k === "text") n.textContent = props[k];
      else if (k in n) n[k] = props[k];
      else n.setAttribute(k, props[k]);
    }
    (kids || []).forEach(function (c) {
      if (c != null) n.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return n;
  }
  function $(id) { return document.getElementById(id); }

  // ---------- Android string value codec ----------
  function decode(s) {
    return s.replace(/\\n/g, "\n").replace(/\\t/g, "\t")
            .replace(/\\'/g, "'").replace(/\\"/g, '"')
            .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
  }
  function escapeAndroid(s) {
    var v = s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
             .replace(/'/g, "\\'").replace(/"/g, '\\"')
             .replace(/\n/g, "\\n").replace(/\t/g, "\\t");
    if (/^\s|\s$/.test(s)) v = '"' + v + '"';   // preserve edge whitespace
    return v;
  }
  function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  // ---------- locale state ----------
  function localeOf() {
    var sel = $("tr-locale").value;
    if (sel === "__new__") return ($("tr-newcode").value || "").trim();
    return sel;
  }
  function isNewLocale(code) { return !data.locales.includes(code); }

  function completeness(code) {
    if (!code || isNewLocale(code)) return { done: 0, total: total };
    var done = 0;
    data.modules.forEach(function (m) {
      m.strings.forEach(function (s) { if (code in s.t) done++; });
    });
    return { done: done, total: total };
  }
  function moduleCompleteness(m, code) {
    if (isNewLocale(code)) return { done: 0, total: m.strings.length };
    var done = 0;
    m.strings.forEach(function (s) { if (code in s.t) done++; });
    return { done: done, total: m.strings.length };
  }

  // ---------- edits ----------
  function originalDecoded(module, name, code) {
    var s = index[module][name];
    return decode((s.t && s.t[code]) || "");
  }
  function currentValue(module, name, code) {
    var e = edits[code] && edits[code][module];
    if (e && name in e) return e[name];
    return originalDecoded(module, name, code);
  }
  function onEdit(module, name, code, value, row) {
    if (!code) return;
    var orig = originalDecoded(module, name, code);
    edits[code] = edits[code] || {};
    edits[code][module] = edits[code][module] || {};
    if (value === orig) {
      delete edits[code][module][name];
      if (!Object.keys(edits[code][module]).length) delete edits[code][module];
      row.classList.remove("tr-changed");
    } else {
      edits[code][module][name] = value;
      row.classList.add("tr-changed");
    }
    updateCounter();
  }
  function countChanges(code) {
    var e = edits[code]; if (!e) return 0;
    var n = 0; for (var m in e) n += Object.keys(e[m]).length;
    return n;
  }
  function updateCounter() {
    var code = localeOf(), n = countChanges(code), btn = $("tr-propose");
    btn.textContent = "Propose " + n + " change" + (n === 1 ? "" : "s");
    btn.disabled = n === 0 || !code;
  }

  // ---------- filtering ----------
  function isUntranslated(s, code) { return isNewLocale(code) || !(code in s.t); }
  function matches(s, module, q) {
    if (!q) return true;
    return s.name.toLowerCase().indexOf(q) >= 0 ||
           decode(s.en).toLowerCase().indexOf(q) >= 0 ||
           module.toLowerCase().indexOf(q) >= 0;
  }

  // ---------- rendering ----------
  function buildRow(module, s, code) {
    var multiline = decode(s.en).length > 42;
    var input = el(multiline ? "textarea" : "input",
      { class: "tr-input", value: currentValue(module, s.name, code) });
    if (code && code.indexOf("ar") === 0) input.dir = "rtl";
    var meta = el("div", { class: "tr-meta" },
      [el("code", { class: "tr-name", text: s.name })].concat(
        (s.args || []).map(function (a) { return el("span", { class: "tr-arg", text: a }); })));
    var row = el("div", { class: "tr-row" },
      [meta, el("div", { class: "tr-en", text: decode(s.en) }), input]);
    if (edits[code] && edits[code][module] && (s.name in edits[code][module]))
      row.classList.add("tr-changed");
    input.addEventListener("input", function () { onEdit(module, s.name, code, input.value, row); });
    return row;
  }

  function render() {
    var code = localeOf();
    var list = $("tr-list"), status = $("tr-status");
    list.textContent = "";
    if (!code) { status.textContent = "Pick a language to begin."; status.hidden = false; return; }

    var q = ($("tr-search").value || "").trim().toLowerCase();
    var modFilter = $("tr-module").value;
    var onlyUntr = $("tr-untranslated").checked;
    var shown = 0, capped = false;

    data.modules.forEach(function (m) {
      if (capped) return;
      if (modFilter && m.module !== modFilter) return;
      var picks = m.strings.filter(function (s) {
        return matches(s, m.module, q) && (!onlyUntr || isUntranslated(s, code));
      });
      if (!picks.length) return;

      var mc = moduleCompleteness(m, code);
      list.appendChild(el("div", { class: "tr-modhead" }, [
        el("code", { text: m.module }),
        el("span", { class: "tr-modpct", text: mc.done + " / " + mc.total + " translated" })
      ]));

      var lastComment = null;
      for (var i = 0; i < picks.length; i++) {
        if (shown >= RENDER_CAP) { capped = true; break; }
        var s = picks[i];
        if (s.comment && s.comment !== lastComment) {
          list.appendChild(el("div", { class: "tr-context", text: s.comment }));
          lastComment = s.comment;
        }
        list.appendChild(buildRow(m.module, s, code));
        shown++;
      }
    });

    if (!shown) {
      status.textContent = onlyUntr
        ? "Nothing untranslated here — uncheck “Only untranslated” to revise existing strings."
        : "No strings match your filter.";
      status.hidden = false;
    } else if (capped) {
      status.textContent = "Showing the first " + RENDER_CAP +
        " strings — use the module filter or search to narrow down.";
      status.hidden = false;
    } else {
      status.hidden = true;
    }
  }

  function updateProgress() {
    var code = localeOf(), wrap = $("tr-progress");
    if (!code) { wrap.hidden = true; return; }
    var c = completeness(code), pct = c.total ? Math.round(100 * c.done / c.total) : 0;
    wrap.hidden = false;
    $("tr-bar-fill").style.width = pct + "%";
    $("tr-progress-label").textContent =
      pct + "% — " + c.done + " / " + c.total +
      (isNewLocale(code) ? " (new language)" : " translated");
  }

  // ---------- propose (GitHub handoff) ----------
  function patch(text, module, moduleEdits) {
    for (var name in moduleEdits) {
      var esc = escapeAndroid(moduleEdits[name]);
      var re = new RegExp('(<string\\s+name="' + escapeRegex(name) +
        '"[^>]*>)([\\s\\S]*?)(</string>)');
      if (re.test(text)) {
        (function (esc) {
          text = text.replace(re, function (m, p1, p2, p3) { return p1 + esc + p3; });
        })(esc);
      } else {
        (function (name, esc) {
          text = text.replace(/<\/resources>\s*$/, function () {
            return '    <string name="' + name + '">' + esc + '</string>\n</resources>\n';
          });
        })(name, esc);
      }
    }
    return text;
  }
  function newFile(moduleEdits) {
    var lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>'];
    for (var name in moduleEdits)
      lines.push('    <string name="' + name + '">' + escapeAndroid(moduleEdits[name]) + '</string>');
    lines.push('</resources>', '');
    return lines.join("\n");
  }

  function proposeCard(module, code) {
    var path = module + "/src/main/res/values-" + code + "/strings.xml";
    var card = el("div", { class: "tr-card" }, [el("h3", {}, [el("code", { text: path })])]);
    var ta = el("textarea", { class: "tr-out", readonly: true, rows: 12 });
    card.appendChild(ta);
    var actions = el("div", { class: "tr-card-actions" });
    card.appendChild(actions);

    function wire(content, url, label) {
      ta.value = content;
      var copy = el("button", { class: "tr-copy", text: "Copy" });
      copy.addEventListener("click", function () {
        navigator.clipboard.writeText(content).then(function () {
          copy.textContent = "Copied!";
          setTimeout(function () { copy.textContent = "Copy"; }, 1500);
        });
      });
      actions.appendChild(copy);
      actions.appendChild(el("a", { class: "tr-open", href: url, target: "_blank", rel: "noopener", text: label }));
    }

    var moduleEdits = edits[code][module];
    if (isNewLocale(code)) {
      var content = newFile(moduleEdits);
      var enc = encodeURIComponent(content);
      var base = "https://github.com/" + REPO + "/new/" + BRANCH + "?filename=" + encodeURIComponent(path);
      // Prefill only when small enough to fit a URL reliably; else copy-paste.
      wire(content, enc.length < 6000 ? base + "&value=" + enc : base, "Create file on GitHub");
    } else {
      ta.value = "Fetching current file…";
      fetch("https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/" + path)
        .then(function (r) { if (!r.ok) throw new Error(r.status); return r.text(); })
        .then(function (raw) {
          wire(patch(raw, module, moduleEdits),
               "https://github.com/" + REPO + "/edit/" + BRANCH + "/" + path,
               "Open in GitHub editor");
        })
        .catch(function () {
          wire("Couldn't fetch the current file. Open it on GitHub and apply your edits by hand:\n" +
               JSON.stringify(moduleEdits, null, 2),
               "https://github.com/" + REPO + "/edit/" + BRANCH + "/" + path,
               "Open in GitHub editor");
        });
    }
    return card;
  }

  function propose() {
    var code = localeOf(); if (!code) return;
    var e = edits[code]; if (!e) return;
    var mods = Object.keys(e).filter(function (m) { return Object.keys(e[m]).length; });
    var body = $("tr-modal-body"); body.textContent = "";
    mods.forEach(function (m) { body.appendChild(proposeCard(m, code)); });
    $("tr-modal").hidden = false;
  }

  // ---------- init ----------
  function init() {
    var sel = $("tr-locale");
    sel.appendChild(el("option", { value: "", text: "Select a language…" }));
    data.locales.forEach(function (loc) {
      sel.appendChild(el("option", { value: loc, text: LOCALE_NAMES[loc] || loc }));
    });
    sel.appendChild(el("option", { value: "__new__", text: "➕ Add a new language…" }));

    var ms = $("tr-module");
    data.modules.forEach(function (m) {
      ms.appendChild(el("option", { value: m.module, text: m.module }));
    });

    function onLocaleChange() {
      $("tr-newlang").hidden = sel.value !== "__new__";
      var code = localeOf();
      $("tr-filters").hidden = !code;
      $("tr-actionbar").hidden = !code;
      updateProgress(); updateCounter(); render();
    }
    sel.addEventListener("change", onLocaleChange);
    $("tr-newcode").addEventListener("input", function () { updateProgress(); updateCounter(); render(); });
    $("tr-search").addEventListener("input", render);
    $("tr-module").addEventListener("change", render);
    $("tr-untranslated").addEventListener("change", render);
    $("tr-propose").addEventListener("click", propose);
    $("tr-modal-close").addEventListener("click", function () { $("tr-modal").hidden = true; });
    $("tr-modal").addEventListener("click", function (ev) { if (ev.target === $("tr-modal")) $("tr-modal").hidden = true; });

    $("tr-status").textContent = "Pick a language to begin.";
    $("tr-status").hidden = false;
  }

  fetch("i18n/strings.json")
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (json) {
      data = json;
      data.modules.forEach(function (m) {
        index[m.module] = {};
        m.strings.forEach(function (s) { index[m.module][s.name] = s; total++; });
      });
      init();
    })
    .catch(function (err) {
      $("tr-status").textContent = "Failed to load strings.json: " + err.message;
    });
})();
