// When the user selects a tab that doesn't exist in every group (e.g. "Native"),
// Material's linked-tabs leaves groups without that tab on their previous
// selection.  This script forces those groups to fall back to "Kotlin".
//
// Uses Material's "data-md-switching" flag on the fallback click so that
// Material's cross-group sync doesn't cascade to other groups.
// Also patches __tabs in localStorage for correct behavior on page reload.
;(function () {
  var FALLBACK = "Kotlin"
  var timer = null
  var scope = location.pathname

  function applyFallback() {
    var clicked = null
    var maxTabs = 0
    document.querySelectorAll(".tabbed-set").forEach(function (set) {
      var n = set.querySelectorAll(".tabbed-labels label").length
      if (n > maxTabs) {
        maxTabs = n
        var checked = set.querySelector("input:checked")
        if (checked) {
          var lbl = document.querySelector('label[for="' + checked.id + '"]')
          if (lbl) clicked = lbl.textContent.trim()
        }
      }
    })
    if (!clicked || clicked === FALLBACK) return

    var didFallback = false
    document.querySelectorAll(".tabbed-set").forEach(function (set) {
      var labels = set.querySelectorAll(".tabbed-labels label")
      var hasClicked = false
      var fallbackLabel = null

      labels.forEach(function (l) {
        var t = l.textContent.trim()
        if (t === clicked) hasClicked = true
        if (t === FALLBACK) fallbackLabel = l
      })

      if (!hasClicked && fallbackLabel) {
        var input = document.getElementById(fallbackLabel.htmlFor)
        if (input && !input.checked) {
          fallbackLabel.setAttribute("data-md-switching", "")
          input.click()
          didFallback = true
        }
      }
    })

    // Patch __tabs so page reload restores correctly:
    // ["Native", "Kotlin", ...rest] → Material restores Native where available,
    // Kotlin where not
    if (didFallback) {
      try {
        var key = scope + ".__tabs"
        var tabs = JSON.parse(localStorage.getItem(key) || "[]")
        tabs = tabs.filter(function (t) { return t !== FALLBACK && t !== clicked })
        tabs.unshift(FALLBACK)
        tabs.unshift(clicked)
        localStorage.setItem(key, JSON.stringify(tabs))
      } catch (e) {}
    }
  }

  document.addEventListener("change", function (e) {
    if (!e.target.name || !e.target.name.startsWith("__tabbed_")) return
    clearTimeout(timer)
    timer = setTimeout(applyFallback, 80)
  }, true)
})()
