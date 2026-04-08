// When the user selects a tab that doesn't exist in every group,
// Material's linked-tabs leaves groups without that tab on their previous
// selection.  This script forces those groups to a sensible fallback.
//
// Two families of tabs exist across pages:
//   Language tabs: Kotlin, Java, Native
//   Build tabs:    Maven, Gradle (Java), Gradle (Kotlin), Gradle (Native)
//
// When clicking a language tab, build-tab groups should switch to the
// matching build variant, and vice versa.
//
// Uses Material's "data-md-switching" flag on the fallback click so that
// Material's cross-group sync doesn't cascade.
// Also patches __tabs in localStorage for correct behavior on page reload.
;(function () {
  var scope = location.pathname
  var applying = false  // re-entrancy guard

  // For a clicked tab that is missing in some group, return an ordered list
  // of fallback labels to try.
  var FALLBACK_MAP = {
    // language → build
    "Native":           ["Gradle (Native)", "Kotlin"],
    "Java":             ["Gradle (Java)", "Maven", "Kotlin"],
    "Kotlin":           ["Gradle (Kotlin)"],
    // build → language
    "Gradle (Native)":  ["Native", "Kotlin"],
    "Gradle (Java)":    ["Java"],
    "Gradle (Kotlin)":  ["Kotlin"],
    "Maven":            ["Java"],
  }

  // Track which label the user actually clicked (not fallback-triggered)
  var userClicked = null

  // Capture the actual user click on a tab label
  document.addEventListener("click", function (e) {
    if (applying) return
    var label = e.target.closest(".tabbed-labels label")
    if (label) userClicked = label.textContent.trim()
  }, true)

  function applyFallback() {
    if (applying) return
    var clicked = userClicked
    userClicked = null
    if (!clicked) return

    var fallbacks = FALLBACK_MAP[clicked]
    if (!fallbacks) return

    applying = true
    var didFallback = false

    document.querySelectorAll(".tabbed-set").forEach(function (set) {
      var labels = set.querySelectorAll(".tabbed-labels label")
      var hasClicked = false

      labels.forEach(function (l) {
        if (l.textContent.trim() === clicked) hasClicked = true
      })

      if (hasClicked) return

      // Find the first available fallback in this group
      var bestFallback = null
      for (var i = 0; i < fallbacks.length; i++) {
        labels.forEach(function (l) {
          if (!bestFallback && l.textContent.trim() === fallbacks[i]) bestFallback = l
        })
        if (bestFallback) break
      }

      if (bestFallback) {
        var input = document.getElementById(bestFallback.htmlFor)
        if (input && !input.checked) {
          bestFallback.setAttribute("data-md-switching", "")
          input.click()
          didFallback = true
        }
      }
    })

    // Patch __tabs so page reload restores correctly
    if (didFallback) {
      try {
        var key = scope + ".__tabs"
        var tabs = JSON.parse(localStorage.getItem(key) || "[]")
        var used = [clicked]
        fallbacks.forEach(function (f) { used.push(f) })
        tabs = tabs.filter(function (t) { return used.indexOf(t) < 0 })
        for (var i = fallbacks.length - 1; i >= 0; i--) tabs.unshift(fallbacks[i])
        tabs.unshift(clicked)
        localStorage.setItem(key, JSON.stringify(tabs))
      } catch (e) {}
    }

    applying = false
  }

  document.addEventListener("change", function (e) {
    if (applying) return
    if (!e.target.name || !e.target.name.startsWith("__tabbed_")) return
    setTimeout(applyFallback, 80)
  }, true)
})()
