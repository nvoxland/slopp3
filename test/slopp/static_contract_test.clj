(ns slopp.static-contract-test
  "ONE contract for the reader port `slopp.web.static/mount-routes` accepts —
  the first verified-fake style suite in this repo. The RUNS live with their
  adapters (`slopp.mcp.http-test`, `slopp.web-test`); only the suite is here.

  That split was taught by the module gate rather than chosen: filing the runs
  here would have required `^:export` on a package-private reader purely so a
  test could see it, which is test-induced damage the visibility rules refuse
  on your behalf. A contract belongs with neither implementation; a RUN belongs
  inside the module whose implementation it exercises.

  Why it exists: the two readers have already disagreed in production. A mount
  prefix written `public/` asked for `public//app.css`; the filesystem reader
  normalised the doubled separator away and the store-backed one did not,
  because it looks the string up in a manifest. The same config worked in a
  built app and served nothing under --live. Nothing compared them, so the fix
  went into `mount-routes` and the divergence itself stayed unstated.

  What is asserted is the INTERSECTION of what both actually promise. Where
  they genuinely differ that is a caller obligation, recorded rather than
  legislated into one of them."
  (:require [clojure.test :refer [is testing]]))
